package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.OptimizationRun;
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
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * The finishing-latch fix itself (WP1 plan, Step 3): Timefold 1.33.0 flips {@code SolverStatus} to
 * {@code NOT_SOLVING} BEFORE the final-best-solution consumer runs (verified detail, see {@link
 * LiveSolutionRegistry}'s javadoc) — so a plan must stay 409-guarded for the whole time its
 * writeback is in flight, not just while {@code SolverManager} reports it as solving. Proven by
 * parking {@link SolveResultWriteback#persist} on a latch: while parked, {@code SolverStatus} has
 * already gone {@code NOT_SOLVING}, yet both a new solve start and an unrelated plan mutation must
 * still be refused (409) — the {@code ActiveSolveGuardInterceptor}'s default-deny guard included.
 * Releasing the latch lets the run reach FINISHED, after which a new solve is accepted again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class FinishingLatchIntegrationTest {

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
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private SolveCoordinator solveCoordinator;
    @Autowired
    private SolverManager<GroupPlanSolution, String> solverManager;

    @MockitoSpyBean
    private SolveResultWriteback solveResultWriteback;

    private String loadSmallPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void thePlanStays409GuardedThroughTheEntireFinishingWindowNotJustWhileTimefoldReportsSolving() throws Exception {
        String planId = loadSmallPlan();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            parked.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test never released the writeback latch");
            }
            return invocation.callRealMethod();
        }).when(solveResultWriteback).persist(any(), any());

        String runId = solveCoordinator.startSolve(planId, SolveProfile.FAST);

        assertThat(parked.await(60, TimeUnit.SECONDS)).as("solve never reached the parked writeback").isTrue();
        // The verified ordering this whole fix exists for: SolverStatus has already flipped by the
        // time persist() is entered.
        assertThat(solverManager.getSolverStatus(planId)).isEqualTo(SolverStatus.NOT_SOLVING);

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        release.countDown();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        // The latch is released now - a fresh solve is accepted again.
        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(status().isAccepted());
    }
}
