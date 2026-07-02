package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
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
import se.klubb.groupplanner.solver.run.OptimizationRunService;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * v0.2.0 (COACH-OPTIONAL SOLVING, first-field-test feedback: "om man inte lägger till tränare så
 * ska man ändå kunna optimera grupperna"): a plan with ZERO {@code coach_profile} rows must solve
 * end-to-end — feasible, groups+blocks assigned, zero {@code coach_assignment} rows written, and the
 * persisted run summary carries the {@code note} field the UI shows. Both the real solver (FAST
 * profile) and the GREEDY baseline are exercised. Uses the {@code small-10} fixture with its two
 * seeded coach profiles DELETED (cascades to their {@code coach_time_slot} rows, V1__core.sql) —
 * the simplest way to get a fully-populated, otherwise-normal plan with genuinely zero coaches,
 * without hand-building a whole fixture from scratch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class CoachlessSolveIntegrationTest {

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
    private CoachAssignmentRepository coachAssignmentRepository;
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

    /** Loads {@code small-10} and then deletes every seeded coach profile for the plan — the
     * resulting plan has real groups/blocks/participants but zero coaches. */
    private String loadCoachlessPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("small-10");

        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        assertThat(coaches).as("small-10's own README documents seeded coaches - precondition for this test").isNotEmpty();
        coaches.forEach(c -> coachProfileRepository.deleteById(c.id()));
        assertThat(coachProfileRepository.findByActivityPlanId(planId)).isEmpty();

        return planId;
    }

    @Test
    void fullSolveOnACoachlessPlanIsFeasibleAndCarriesTheNoCoachesNote() throws Exception {
        String planId = loadCoachlessPlan();

        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        // Feasible: no coach-related (or any other) hard violation - the pre-fix bug produced
        // negative hardScore (one coachRequirementHard violation per group, m7-notes.md).
        assertThat(run.score()).as("coach-less solve must be feasible").startsWith("0hard");

        // groups + schedule optimized: every player placed, every group's block assigned - small-10
        // has no capacity pressure (SolveControllerIntegrationTest's own fixture invariant).
        List<PlayerAssignment> assignments = playerAssignmentRepository.findByActivityPlanId(planId);
        assertThat(assignments).hasSize(10);
        long assignedCount = assignments.stream().filter(pa -> pa.groupId() != null).count();
        assertThat(assignedCount).isEqualTo(10);
        assertThat(trainingGroupRepository.findByActivityPlanId(planId))
                .allSatisfy(g -> assertThat(g.assignedTrainingBlockId()).isNotNull());

        // No CoachSlots created -> no coach_assignment rows written, for any group.
        assertThat(coachAssignmentRepository.findByActivityPlanId(planId)).isEmpty();

        // The note field the UI can show, present ONLY because coachCount == 0.
        JsonNode summary = objectMapper.readTree(run.resultSummaryJson());
        assertThat(summary.get("note").asText()).isEqualTo(OptimizationRunService.NOTE_NO_COACHES);
    }

    @Test
    void greedyBaselineAlsoWorksOnACoachlessPlan() throws Exception {
        String planId = loadCoachlessPlan();

        String response = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"GREEDY\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("status").asText()).isEqualTo("FINISHED");
        // Greedy has no capacity/overlap awareness (m6b-notes.md), but with zero coaches there is
        // simply nothing for it to mis-assign on the coach axis - small-10 has no other hard-violating
        // pressure either, so this should also come back clean.
        assertThat(json.get("feasible").asBoolean()).isTrue();

        String runId = json.get("runId").asText();
        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        JsonNode summary = objectMapper.readTree(run.resultSummaryJson());
        assertThat(summary.get("note").asText()).isEqualTo(OptimizationRunService.NOTE_NO_COACHES);
        assertThat(coachAssignmentRepository.findByActivityPlanId(planId)).isEmpty();
    }

    @Test
    void optimizeCoachesTrueOnAZeroCoachPlanIsANoOpNotA400() throws Exception {
        String planId = loadCoachlessPlan();

        // optimize.coaches defaults to true; explicitly setting it true must behave identically -
        // no exception, no 400 - even though there is nothing to optimize on that axis.
        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\",\"optimize\":{\"players\":true,\"schedule\":true,\"coaches\":true}}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });
        assertThat(optimizationRunRepository.findById(runId).orElseThrow().score()).startsWith("0hard");
    }

    @Test
    void capacityEndpointReportsNoCoachesAsInfoNotShortage() throws Exception {
        String planId = loadCoachlessPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/capacity").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.coachCount").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.noCoaches").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.coachShortageRisk").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.coachShortageMessage")
                        .value("Inga tränare registrerade"));
    }
}
