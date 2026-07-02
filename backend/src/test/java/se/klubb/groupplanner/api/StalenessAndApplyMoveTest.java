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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
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
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * Staleness flip test + apply-move endpoint tests (this milestone's task item 6): manual move -&gt;
 * {@code stale=true}, re-solve -&gt; fresh; apply-move 409 while solving + revision bump on success.
 * Exercises the REAL HTTP endpoints end-to-end (docs/design/04-solver.md §11.6/§14.2), on the {@code
 * small-10} fixture for fast convergence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StalenessAndApplyMoveTest {

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

    private String solveAndAwaitFinished(String planId, String profile) throws Exception {
        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"" + profile + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });
        return runId;
    }

    @Test
    void manualMoveMarksTheRunStaleThenAFreshSolveIsFreshAgain() throws Exception {
        String planId = loadSmallPlan();
        String runId1 = solveAndAwaitFinished(planId, "FAST");

        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        String otherGroupId = trainingGroupRepository.findByActivityPlanId(planId).stream()
                .filter(g -> !g.id().equals(playerAssignmentRepository.findByParticipantProfileId(someone.id()).orElseThrow().groupId()))
                .findFirst().orElseThrow().id();

        // Fresh immediately after solve.
        JsonNode fresh = fetchPersonExplanation(planId, runId1, someone.id());
        assertThat(fresh.get("stale").asBoolean()).isFalse();

        // Manual move -> stale=true for runId1.
        mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + otherGroupId + "\"}"))
                .andExpect(status().isOk());

        PlayerAssignment moved = playerAssignmentRepository.findByParticipantProfileId(someone.id()).orElseThrow();
        assertThat(moved.groupId()).isEqualTo(otherGroupId);
        assertThat(moved.source()).isEqualTo(PlayerAssignment.SOURCE_MANUAL);

        JsonNode afterMove = fetchPersonExplanation(planId, runId1, someone.id());
        assertThat(afterMove.get("stale").asBoolean()).isTrue();

        // Re-solve -> a NEW run, fresh relative to ITSELF.
        String runId2 = solveAndAwaitFinished(planId, "FAST");
        assertThat(runId2).isNotEqualTo(runId1);
        JsonNode afterResolve = fetchPersonExplanation(planId, runId2, someone.id());
        assertThat(afterResolve.get("stale").asBoolean()).isFalse();

        // The OLD run is now stale too (a newer solve changed the persisted assignments it reads).
        JsonNode oldRunAfterResolve = fetchPersonExplanation(planId, runId1, someone.id());
        assertThat(oldRunAfterResolve.get("stale").asBoolean()).isTrue();
    }

    private JsonNode fetchPersonExplanation(String planId, String runId, String participantProfileId) throws Exception {
        String response = mockMvc.perform(get(
                        "/api/plans/" + planId + "/runs/" + runId + "/explanations/players/" + participantProfileId)
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void applyMoveReturns409WhileASolveIsRunningAndBumpsRevisionOnSuccess() throws Exception {
        String planId = loadSmallPlan();
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        String targetGroupId = trainingGroupRepository.findByActivityPlanId(planId).get(0).id();

        int revisionBefore = activityPlanRepository.getPlanRevision(planId);
        mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + targetGroupId + "\"}"))
                .andExpect(status().isOk());
        assertThat(activityPlanRepository.getPlanRevision(planId)).isEqualTo(revisionBefore + 1);

        // 409 while a solve is running.
        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"THOROUGH\"}"))
                .andExpect(status().isAccepted());
        try {
            mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                            .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupId\":\"" + targetGroupId + "\"}"))
                    .andExpect(status().isConflict());
        } finally {
            solveCoordinator.cancelSolve(planId);
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(solveCoordinator.status(planId).status()).isEqualTo("NOT_SOLVING"));
        }
    }

    @Test
    void applyMoveToWaitlistSetsGroupIdNull() throws Exception {
        String planId = loadSmallPlan();
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        playerAssignmentRepository.lockToGroup(someone.id(), trainingGroupRepository.findByActivityPlanId(planId).get(0).id());
        playerAssignmentRepository.unlock(someone.id());

        mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        PlayerAssignment result = playerAssignmentRepository.findByParticipantProfileId(someone.id()).orElseThrow();
        assertThat(result.groupId()).isNull();
        assertThat(result.source()).isEqualTo(PlayerAssignment.SOURCE_MANUAL);
    }

    @Test
    void applyMoveRejectsALockedAssignment() throws Exception {
        String planId = loadSmallPlan();
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        String groupId = trainingGroupRepository.findByActivityPlanId(planId).get(0).id();
        playerAssignmentRepository.lockToGroup(someone.id(), groupId);

        mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /** M7 review fix m5: moving a participant to the group they are ALREADY in is a 200 no-op —
     * neither the revision bumps (no reason to invalidate explanations) nor does {@code source}
     * flip to {@code manual} (provenance stays honest). */
    @Test
    void applyMoveToCurrentGroupIsANoOpWithoutBumpOrSourceFlip() throws Exception {
        String planId = loadSmallPlan();
        String runId = solveAndAwaitFinished(planId, "FAST");
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        PlayerAssignment before = playerAssignmentRepository.findByParticipantProfileId(someone.id()).orElseThrow();
        assertThat(before.groupId()).isNotNull(); // small-10 places everyone
        assertThat(before.source()).isEqualTo(PlayerAssignment.SOURCE_SOLVER);
        int revisionBefore = activityPlanRepository.getPlanRevision(planId);

        mockMvc.perform(post("/api/plans/" + planId + "/assignments/" + someone.id() + "/move")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + before.groupId() + "\"}"))
                .andExpect(status().isOk());

        PlayerAssignment after = playerAssignmentRepository.findByParticipantProfileId(someone.id()).orElseThrow();
        assertThat(after.groupId()).isEqualTo(before.groupId());
        assertThat(after.source()).isEqualTo(PlayerAssignment.SOURCE_SOLVER); // NOT flipped to manual
        assertThat(activityPlanRepository.getPlanRevision(planId)).isEqualTo(revisionBefore); // NOT bumped

        // And the run's explanation is accordingly still fresh.
        JsonNode explanation = fetchPersonExplanation(planId, runId, someone.id());
        assertThat(explanation.get("stale").asBoolean()).isFalse();
    }

    /** M7 review fix M2: level recompute is a placement-relevant mutation — a prior run's
     * explanation must go stale after it (its level-based sentences may now be false). */
    @Test
    void recomputeLevelsBumpsRevisionAndMarksPriorRunStale() throws Exception {
        String planId = loadSmallPlan();
        String runId = solveAndAwaitFinished(planId, "FAST");
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        assertThat(fetchPersonExplanation(planId, runId, someone.id()).get("stale").asBoolean()).isFalse();
        int revisionBefore = activityPlanRepository.getPlanRevision(planId);

        mockMvc.perform(post("/api/plans/" + planId + "/participants/recompute-levels")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());

        assertThat(activityPlanRepository.getPlanRevision(planId)).isEqualTo(revisionBefore + 1);
        assertThat(fetchPersonExplanation(planId, runId, someone.id()).get("stale").asBoolean()).isTrue();
    }

    /** M7 review fix M2: group (re)generation reshapes the very groups an explanation refers to —
     * a prior run's explanation must go stale after it. */
    @Test
    void groupGenerateBumpsRevisionAndMarksPriorRunStale() throws Exception {
        String planId = loadSmallPlan();
        String runId = solveAndAwaitFinished(planId, "FAST");
        ParticipantProfile someone = participantProfileRepository.findByActivityPlanId(planId).get(0);
        assertThat(fetchPersonExplanation(planId, runId, someone.id()).get("stale").asBoolean()).isFalse();
        int revisionBefore = activityPlanRepository.getPlanRevision(planId);

        mockMvc.perform(post("/api/plans/" + planId + "/groups/generate")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());

        assertThat(activityPlanRepository.getPlanRevision(planId)).isEqualTo(revisionBefore + 1);
        assertThat(fetchPersonExplanation(planId, runId, someone.id()).get("stale").asBoolean()).isTrue();
    }
}
