package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/** {@code POST /api/plans/{planId}/solve {"profile":"GREEDY"}} (spec §16.7): runs synchronously, no
 * Timefold, writes results back the same way a real solve does. */
@SpringBootTest
@AutoConfigureMockMvc
class GreedySolveIntegrationTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void greedyProfileRunsSynchronouslyAndWritesResultsBack() throws Exception {
        String planId = loadPlan();

        String response = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"GREEDY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                // M6b review fix F6: the synchronous response surfaces the full outcome. small-10's
                // unconstrained greedy result is hard-feasible (golden: 0hard/0medium/-88600soft).
                .andExpect(jsonPath("$.score").isNotEmpty())
                .andExpect(jsonPath("$.hardViolations").value(0))
                .andExpect(jsonPath("$.feasible").value(true))
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(response).get("runId").asText();
        assertThat(runId).isNotBlank();

        // Synchronous: no polling needed, the run is FINISHED by the time the HTTP call returns.
        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        assertThat(run.score()).isNotBlank();

        List<PlayerAssignment> assignments = playerAssignmentRepository.findByActivityPlanId(planId);
        assertThat(assignments).hasSize(10);
        assertThat(assignments).allSatisfy(pa -> assertThat(pa.source()).isEqualTo(PlayerAssignment.SOURCE_SOLVER));

        assertThat(trainingGroupRepository.findByActivityPlanId(planId))
                .allSatisfy(g -> assertThat(g.assignedTrainingBlockId()).isNotNull());
    }

    /** M6b review fix F6: greedy has NO time-availability awareness (it places everyone), so a
     * participant whose {@code cannotTimes} covers EVERY slot is guaranteed to be placed on a time
     * they cannot attend — {@code timeAvailabilityHard} fires and the synchronous response must
     * surface it ({@code feasible:false}, {@code hardViolations >= 1}) instead of letting the UI
     * read the {@code FINISHED} baseline as a clean success. */
    @Test
    void greedyOnAConstrainedPlanSurfacesHardViolationsInTheResponse() throws Exception {
        String planId = loadPlan();
        String participantId = participantProfileRepository.findByActivityPlanId(planId).get(0).id();
        String cannotTimesFieldId = fieldDefinitionRepository.findGlobalByKey("cannotTimes").orElseThrow().id();
        List<String> allSlotIds = timeSlotRepository.findByActivityPlanId(planId).stream()
                .map(se.klubb.groupplanner.domain.TimeSlot::id)
                .toList();
        customFieldValueRepository.upsert(
                cannotTimesFieldId, "participant", participantId, objectMapper.writeValueAsString(allSlotIds));

        String response = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"GREEDY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.feasible").value(false))
                .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(response);
        assertThat(json.get("hardViolations").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(json.get("score").asText()).startsWith("-"); // hard < 0 leads the score string.
    }

    @Test
    void greedyProfileIsCaseInsensitive() throws Exception {
        String planId = loadPlan();

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"greedy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"));
    }
}
