package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TrainingGroup;
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
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * The revision CAS itself (WP1 plan, Step 2): {@link SolveResultWriteback#persist} refuses to write
 * back a solve whose {@code plan_revision} baseline ({@link
 * se.klubb.groupplanner.solver.assemble.AssembledProblem#planRevisionAtAssemble}) no longer matches
 * the plan's CURRENT revision — proof that some other mutation committed while this solve was
 * running, and overwriting it would silently discard that change.
 *
 * <p>Simulated by parking the writeback on a latch (same harness as {@link
 * FinishingLatchIntegrationTest}) and bumping the plan's revision directly via the repository while
 * parked — standing in for "a manual move/lock happened mid-solve" without needing a second, truly
 * concurrent mutating request.
 */
@SpringBootTest
@ExtendWith(ActiveSolveCleanup.class)
class StaleWritebackCasTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

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
    void aRevisionBumpWhileTheWritebackIsParkedFailsTheRunAndLeavesAssignmentsUntouched() throws Exception {
        String planId = loadSmallPlan();
        Map<String, String> groupByParticipantBefore = groupByParticipant(planId);
        Map<String, String> blockByGroupBefore = blockByGroup(planId);

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

        // Stands in for "a manual move/lock committed while this solve was running".
        activityPlanRepository.bumpRevision(planId);

        release.countDown();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FAILED);
        });

        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.resultSummaryJson()).contains(
                "Planen ändrades medan optimeringen pågick – resultatet sparades inte. Kör optimeringen igen.");

        // The stale writeback never touched a single row.
        assertThat(groupByParticipant(planId)).isEqualTo(groupByParticipantBefore);
        assertThat(blockByGroup(planId)).isEqualTo(blockByGroupBefore);
        assertThat(playerAssignmentRepository.findByActivityPlanId(planId))
                .allSatisfy(pa -> assertThat(pa.source()).isNotEqualTo(PlayerAssignment.SOURCE_SOLVER));
    }

    private Map<String, String> groupByParticipant(String planId) {
        // Collectors.toMap rejects null values (its default merge function calls Map.merge, which
        // NPEs on a null value) - group_id is legitimately null pre-solve, so build the map by hand.
        Map<String, String> map = new java.util.HashMap<>();
        for (PlayerAssignment pa : playerAssignmentRepository.findByActivityPlanId(planId)) {
            map.put(pa.participantProfileId(), pa.groupId());
        }
        return map;
    }

    private Map<String, String> blockByGroup(String planId) {
        Map<String, String> map = new java.util.HashMap<>();
        for (TrainingGroup g : trainingGroupRepository.findByActivityPlanId(planId)) {
            map.put(g.id(), g.assignedTrainingBlockId());
        }
        return map;
    }
}
