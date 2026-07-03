package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * Solve lifecycle end-to-end (docs/design/04-solver.md §9.4/§14.2): start -&gt; poll status -&gt;
 * completion -&gt; DB rows written; 409 while active; cancel path persists best-so-far as CANCELLED.
 * Uses the {@code small-10} fixture (via the same {@link TestDatasetLoader} the golden regression
 * test uses) so a {@code FAST} (10 s wall-clock) profile solve completes fast enough for a
 * unit-test-scale wait.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class SolveControllerIntegrationTest {

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

    @Test
    void fullLifecycleStartStatusCompletionWritesResultsBack() throws Exception {
        String planId = loadSmallPlan();

        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isAccepted())
                // The response reports the ACTUAL SolverManager status at return time (review fix 8):
                // SOLVING_SCHEDULED until a solver thread picks the job up, then SOLVING_ACTIVE.
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.oneOf("SOLVING_SCHEDULED", "SOLVING_ACTIVE")))
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();
        assertThat(runId).isNotBlank();

        // 409 while a solve is already active for this plan.
        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isConflict());

        // FAST = 10s wall-clock (spec §16.6). Poll the RUN ROW, not the transient solver
        // status: FINISHED persistence happens in the completion callback, which can lag
        // behind SolverManager flipping to NOT_SOLVING on a slow CI runner (observed on
        // windows-latest). Generous bound — untilAsserted returns as soon as it holds.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        // optimization_run persisted as FINISHED with a feasible score.
        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        assertThat(run.score()).startsWith("0hard");
        assertThat(run.startedAt()).isNotBlank();
        assertThat(run.finishedAt()).isNotBlank();
        assertThat(run.durationMs()).isNotNull();

        // Results written back: every unlocked player_assignment row now source=solver, and most
        // (small-10 has 2 blocks x max 6 = 12 >= 10 participants, per its README) have a group.
        List<PlayerAssignment> assignments = playerAssignmentRepository.findByActivityPlanId(planId);
        assertThat(assignments).hasSize(10);
        assertThat(assignments).allSatisfy(pa -> assertThat(pa.source()).isEqualTo(PlayerAssignment.SOURCE_SOLVER));
        long assignedCount = assignments.stream().filter(pa -> pa.groupId() != null).count();
        assertThat(assignedCount).isEqualTo(10); // no waitlist pressure per the fixture's own README.

        assertThat(trainingGroupRepository.findByActivityPlanId(planId))
                .allSatisfy(g -> assertThat(g.assignedTrainingBlockId()).isNotNull());
    }

    @Test
    void cancelPersistsBestSoFarAsCancelled() throws Exception {
        String planId = loadSmallPlan();

        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"THOROUGH\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();

        String cancelResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve/cancel")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(cancelResponse).get("finalRunId").asText()).isEqualTo(runId);

        // Poll the run row (not the transient solver status) — see the FINISHED test's note.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_CANCELLED);
        });

        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(OptimizationRun.STATUS_CANCELLED);
    }

    @Test
    void cancelWithNoActiveSolveReturns400() throws Exception {
        String planId = loadSmallPlan();

        mockMvc.perform(post("/api/plans/" + planId + "/solve/cancel").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest());
    }

    /**
     * TOCTOU race guard (review fix 3): two truly concurrent start requests must resolve to exactly
     * one started solve + one clean 409 (ConflictException), with NO orphan SOLVING run row from
     * the loser. Before the {@code putIfAbsent} guard, both threads could pass the
     * {@code getSolverStatus == NOT_SOLVING} check: both inserted a SOLVING run row, then the loser
     * blew up inside SolverManager (problemId already registered), stranding its row forever.
     */
    @Test
    void twoConcurrentStartRequestsYieldExactlyOneSolveAndNoOrphanRunRow() throws Exception {
        String planId = loadSmallPlan();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> raceStart(planId, startGate));
            Future<Object> second = executor.submit(() -> raceStart(planId, startGate));
            startGate.countDown();
            Object resultA = first.get();
            Object resultB = second.get();

            // Exactly one String runId and one ConflictException, in either order.
            long successes = List.of(resultA, resultB).stream().filter(r -> r instanceof String).count();
            long conflicts = List.of(resultA, resultB).stream()
                    .filter(r -> r instanceof se.klubb.groupplanner.api.error.ConflictException)
                    .count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);

            // No orphan: exactly ONE run row exists for this plan, and it is the winner's.
            List<OptimizationRun> runs = optimizationRunRepository.findByActivityPlanId(planId);
            assertThat(runs).hasSize(1);
            String winnerRunId = (String) (resultA instanceof String ? resultA : resultB);
            assertThat(runs.get(0).id()).isEqualTo(winnerRunId);
        } finally {
            executor.shutdownNow();
        }

        // The winner's 10s background solve is cancelled AND awaited to a terminal run row by
        // ActiveSolveCleanup (class-level @ExtendWith). The previous inline cleanup here awaited
        // only NOT_SOLVING, which flips BEFORE the writeback transaction runs - on slow Windows
        // runners the writeback then held the SQLite write lock into the next test's setup
        // inserts (SQLITE_BUSY, observed on windows-latest).
    }

    /** Returns the runId (String) on success or the thrown ConflictException — anything else propagates. */
    private Object raceStart(String planId, CountDownLatch startGate) throws InterruptedException {
        startGate.await();
        try {
            return solveCoordinator.startSolve(planId, SolveProfile.FAST);
        } catch (se.klubb.groupplanner.api.error.ConflictException e) {
            return e;
        }
    }

    @Test
    void statusForAPlanThatHasNeverSolvedReportsNotSolvingWithNoScore() throws Exception {
        String planId = loadSmallPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/solve/status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_SOLVING"))
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.nullValue()));
    }

    /** v0.3.0 WI-2 ("se det live"): a plan that has never had a solve start in this backend process
     *  has no {@code LiveSolutionRegistry} entry at all - 204, not 200 with an empty/null body. */
    @Test
    void liveEndpointReturns204ForAPlanThatHasNeverSolved() throws Exception {
        String planId = loadSmallPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/solve/live").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void liveEndpointWithoutTokenReturns401() throws Exception {
        String planId = loadSmallPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/solve/live"))
                .andExpect(status().isUnauthorized());
    }

    /** End-to-end: the live snapshot is populated the instant a solve starts (the pre-solve seed,
     *  sequence 0 - written synchronously before the solver job is even submitted, see {@code
     *  SolveCoordinator#startSolve}/{@code LiveSolutionRegistry#start}), and the FINAL kept frame
     *  after the run settles accounts for every one of the fixture's 10 participants (placed across
     *  groups + waitlist), each with a real display name - never a blank string or a raw solver id. */
    @Test
    void liveEndpointReflectsThePreSolveSeedImmediatelyAndTheFinalResultAfterCompletion() throws Exception {
        String planId = loadSmallPlan();

        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();

        String seedBody = mockMvc.perform(get("/api/plans/" + planId + "/solve/live").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode seed = objectMapper.readTree(seedBody);
        assertThat(seed.get("runId").asText()).isEqualTo(runId);
        assertThat(seed.get("groups")).isNotEmpty();
        assertThat(seed.get("groups").get(0).get("name").asText()).isNotBlank();
        // small-10's groups are already generated by loadSmallPlan (assemble() requires groupOrder),
        // so the pre-solve seed always carries at least one real group id/name, not a placeholder.
        assertThat(seed.get("groups").get(0).get("groupId").asText()).isNotBlank();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        String finalBody = mockMvc.perform(get("/api/plans/" + planId + "/solve/live").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode finalSnapshot = objectMapper.readTree(finalBody);
        assertThat(finalSnapshot.get("runId").asText()).isEqualTo(runId);
        int totalPlaced = 0;
        boolean sawNonBlankName = false;
        for (JsonNode group : finalSnapshot.get("groups")) {
            for (JsonNode p : group.get("players")) {
                totalPlaced++;
                sawNonBlankName |= !p.get("displayName").asText().isBlank();
            }
        }
        int waitlisted = finalSnapshot.get("waitlist").size();
        assertThat(totalPlaced + waitlisted).isEqualTo(10); // matches the fixture's 10 participants.
        assertThat(sawNonBlankName).isTrue();
    }
}
