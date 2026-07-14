package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
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
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * The proxy proof for M9's Step 1 (WP1 plan): {@link SolveResultWriteback#persist} used to be a
 * package-private {@code @Transactional} method on {@code SolveCoordinator} that
 * {@code onFinalBestSolution} called on {@code this} — a self-invocation Spring's proxy-based AOP
 * silently ignores, so the 4-table writeback (player assignments, training-group blocks, coach
 * assignment delete+insert, revision bump) was NOT atomic on the async solve path. Now it is a
 * top-level {@code @Service}'s own {@code @Transactional} method, invoked through the real Spring
 * proxy from a DIFFERENT bean ({@code SolveCoordinator}) — exactly the shape that makes
 * {@code @Transactional} actually apply.
 *
 * <p>Proven here by making the LAST repository call inside {@link SolveResultWriteback#persist}
 * (the coach-assignment insert, which small-10's 2-coach fixture always reaches after the
 * player_assignment/training_group writes earlier in the same method) throw — if the transaction is
 * real, every earlier write in that same method call rolls back too, and the run ends FAILED with
 * the DB byte-for-byte unchanged from its pre-solve state. Exercises the ASYNC path (not
 * {@code runGreedy}, which is itself {@code @Transactional} and would roll back its own
 * {@code failRun} write along with everything else — see the WP1 plan's own note on this).
 */
@SpringBootTest
@ExtendWith(ActiveSolveCleanup.class)
class SolveResultWritebackTransactionalityTest {

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
    private CoachAssignmentRepository coachAssignmentRepository;

    private String loadSmallPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void aWritebackFailureRollsBackEveryEarlierWriteInTheSameSolve() throws Exception {
        String planId = loadSmallPlan();

        // Pre-solve baseline: every player_assignment unassigned, every group's block unassigned.
        Map<String, String> groupByParticipantBefore = groupByParticipant(planId);
        Map<String, String> blockByGroupBefore = blockByGroup(planId);
        assertThat(groupByParticipantBefore.values()).allMatch(v -> v == null);
        assertThat(blockByGroupBefore.values()).allMatch(v -> v == null);

        doThrow(new RuntimeException("simulated coach_assignment insert failure"))
                .when(coachAssignmentRepository).insert(any(), any(), anyBoolean(), any());

        String runId = solveCoordinator.startSolve(planId, SolveProfile.FAST);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FAILED);
        });

        // Nothing this solve's writeback would have touched actually changed - proof the
        // player_assignment/training_group writes earlier in the SAME persist() call were rolled
        // back together with the coach_assignment insert that threw.
        assertThat(groupByParticipant(planId)).isEqualTo(groupByParticipantBefore);
        assertThat(blockByGroup(planId)).isEqualTo(blockByGroupBefore);
        assertThat(playerAssignmentRepository.findByActivityPlanId(planId))
                .allSatisfy(pa -> assertThat(pa.source()).isNotEqualTo(PlayerAssignment.SOURCE_SOLVER));
        assertThat(activityPlanRepository.getPlanRevision(planId)).isZero();
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
