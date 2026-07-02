package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * {@code GET /api/plans/{planId}/assignments} (see {@link AssignmentController} javadoc for why this
 * endpoint exists — a frontend-M6b gap-fill over already-implemented repository methods).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssignmentControllerTest {

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

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void listsEveryPlayerAssignmentForThePlanEvenUnassignedOnes() throws Exception {
        String planId = loadPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/assignments").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(10))
                .andExpect(jsonPath("$.coaches.length()").value(0));
    }

    @Test
    void reflectsALockAfterItIsApplied() throws Exception {
        String planId = loadPlan();
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/plans/" + planId + "/assignments/" + participant.id() + "/lock")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + group.id() + "\"}"))
                .andExpect(status().isOk());

        String response = mockMvc.perform(get("/api/plans/" + planId + "/assignments").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(response);
        boolean foundLocked = false;
        for (var node : json.get("players")) {
            if (node.get("participantProfileId").asText().equals(participant.id())) {
                assertThat(node.get("groupId").asText()).isEqualTo(group.id());
                assertThat(node.get("locked").asBoolean()).isTrue();
                assertThat(node.get("source").asText()).isEqualTo("locked");
                foundLocked = true;
            }
        }
        assertThat(foundLocked).isTrue();
    }

    @Test
    void listForUnknownPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/assignments").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
