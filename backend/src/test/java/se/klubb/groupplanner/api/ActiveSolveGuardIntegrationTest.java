package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TimeSlot;
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
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * The default-deny mutation guard (WP1 plan, Step 4; {@code
 * se.klubb.groupplanner.api.guard.ActiveSolveGuardInterceptor}): while a solve is active for a
 * plan, every plan-scoped mutating endpoint 409s, not just the handful that already called {@code
 * SolveCoordinator#assertNoActiveSolve} explicitly before this fix. The solve's own start/cancel
 * endpoints and the read-only what-if/status/live endpoints are deliberately exempt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class ActiveSolveGuardIntegrationTest {

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
    private SolveCoordinator solveCoordinator;

    private String loadSmallPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void everyPlanScopedMutationIsRefusedWhileASolveIsActiveButSolveControlAndReadsStillWork() throws Exception {
        String planId = loadSmallPlan();
        String seasonId = activityPlanRepository.findById(planId).orElseThrow().seasonPlanId();
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        CoachProfile coach = coachProfileRepository.findByActivityPlanId(planId).get(0);
        TimeSlot timeSlot = timeSlotRepository.findByActivityPlanId(planId).get(0);

        solveCoordinator.startSolve(planId, SolveProfile.THOROUGH);

        mockMvc.perform(post("/api/plans/" + planId + "/groups/generate").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/participants/" + participant.id())
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/plans/" + planId + "/participants/recompute-levels").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/time-slots/" + timeSlot.id())
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/coaches/" + coach.id()).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/plans/" + planId + "/coaches/" + coach.id() + "/availability")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/seasons/" + seasonId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        // Exemptions: the solve's own status/live/cancel endpoints keep working during the guard.
        mockMvc.perform(get("/api/plans/" + planId + "/solve/status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plans/" + planId + "/solve/live").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());

        // Unknown by-id routes still pass through to their normal 404 - the guard only fires once it
        // has actually resolved a plan id, never blindly for an id it couldn't resolve.
        mockMvc.perform(patch("/api/time-slots/does-not-exist")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/plans/" + planId + "/solve/cancel").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());
    }
}
