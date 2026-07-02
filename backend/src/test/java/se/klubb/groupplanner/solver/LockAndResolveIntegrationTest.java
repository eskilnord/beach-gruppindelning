package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TrainingBlock;
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
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * Design M-S2 gate (b): "lock + re-solve golden test" — locks all THREE kinds (player, group
 * schedule, coach) via the same repository methods {@code AssignmentLockController}/{@code
 * GroupController} use, runs a REAL async solve through {@link SolveCoordinator} end-to-end
 * (assembler -&gt; Timefold -&gt; DB writeback), and asserts every locked value survived unchanged
 * while the rest of the plan was still optimized (hard-feasible, every unlocked player placed).
 */
@SpringBootTest
@ExtendWith(ActiveSolveCleanup.class)
class LockAndResolveIntegrationTest {

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
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private SolveCoordinator solveCoordinator;
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
    void lockingAllThreeKindsThenSolvingHonorsEveryLockWhileOptimizingTheRest() throws InterruptedException {
        String planId = loadPlan();
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        TrainingGroup targetGroup = groups.get(0);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        // Deliberately the block a naive/level-sorted assignment would be LEAST likely to choose:
        // the group's own generated level-band placement has no opinion on time, so any block is
        // equally "natural" here - the point is that the lock forces this SPECIFIC one regardless.
        TrainingBlock lockedBlock = blocks.get(blocks.size() - 1);
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        // The LOWEST-level participant (last after GroupGenerator's own level-desc sort would put
        // them in the last group) - locking them into the FIRST (highest-level) group only proves
        // the lock, not a coincidence, since an unpinned solve would have no reason to put them there.
        ParticipantProfile lockedParticipant = participants.get(participants.size() - 1);
        CoachProfile lockedCoach = coachProfileRepository.findByActivityPlanId(planId).get(0);

        playerAssignmentRepository.lockToGroup(lockedParticipant.id(), targetGroup.id());
        trainingGroupRepository.lockToBlock(targetGroup.id(), lockedBlock.id());
        coachAssignmentRepository.lockToGroup(lockedCoach.id(), targetGroup.id());

        String runId = solveCoordinator.startSolve(planId, SolveProfile.FAST);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(run.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        OptimizationRun finished = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(finished.score()).startsWith("0hard");

        // Player lock honored.
        PlayerAssignment playerAfter = playerAssignmentRepository.findByParticipantProfileId(lockedParticipant.id()).orElseThrow();
        assertThat(playerAfter.groupId()).isEqualTo(targetGroup.id());
        assertThat(playerAfter.locked()).isTrue();

        // Group schedule lock honored.
        TrainingGroup groupAfter = trainingGroupRepository.findById(targetGroup.id()).orElseThrow();
        assertThat(groupAfter.assignedTrainingBlockId()).isEqualTo(lockedBlock.id());
        assertThat(groupAfter.locked()).isTrue();

        // Coach lock honored.
        List<CoachAssignment> coachAssignmentsAfter = coachAssignmentRepository.findByGroupId(targetGroup.id());
        assertThat(coachAssignmentsAfter).anySatisfy(ca -> {
            assertThat(ca.coachProfileId()).isEqualTo(lockedCoach.id());
            assertThat(ca.locked()).isTrue();
        });

        // The REST is still optimized: every OTHER (unlocked) participant is placed too (small-10
        // has no waitlist pressure per its own README), across more than just the locked group -
        // proving the solver kept working on the unlocked entities rather than freezing everything.
        List<PlayerAssignment> allPlayers = playerAssignmentRepository.findByActivityPlanId(planId);
        assertThat(allPlayers).allSatisfy(pa -> assertThat(pa.groupId()).isNotNull());
        long distinctGroupsUsed = allPlayers.stream().map(PlayerAssignment::groupId).distinct().count();
        assertThat(distinctGroupsUsed).isGreaterThan(1);
    }
}
