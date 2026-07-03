package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
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
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause B) cold-start
 * assembly: {@code assemble(..., coldStart=true)} must null out the initial seed for every UNLOCKED
 * {@code PlayerAssignment.group}/{@code GroupSchedule.trainingBlock}/{@code CoachSlot.coach} while
 * leaving locked/pinned entities exactly as a warm start would (their seed IS the value {@code
 * @PlanningPin} freezes, cold or not). Reuses {@code OptimizeSelectionTest}'s "small-10" fixture
 * loading pattern (2 groups, 2 coaches per its config.csv/coaches.csv).
 */
@SpringBootTest
class ColdStartAssemblyTest {

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
    private SolverInputAssembler assembler;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    /** Assigns every group/player/coach a real value, LOCKING exactly the first of each kind
     *  (group 0's schedule, participant 0, coach 0) via the real lock writeback methods and leaving
     *  every other one assigned-but-unlocked - so a single fixture exercises both "locked survives
     *  cold start" and "unlocked gets nulled by cold start" side by side. */
    private void scheduleFullyWithOneLockedOfEachKind(String planId) {
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        trainingGroupRepository.lockToBlock(groups.get(0).id(), blocks.get(0).id());
        for (int i = 1; i < groups.size(); i++) {
            trainingGroupRepository.updateAssignedTrainingBlock(groups.get(i).id(), blocks.get(i % blocks.size()).id());
        }

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        playerAssignmentRepository.lockToGroup(participants.get(0).id(), groups.get(0).id());
        for (int i = 1; i < participants.size(); i++) {
            playerAssignmentRepository.updateGroupAndSource(
                    participants.get(i).id(), groups.get(i % groups.size()).id(), PlayerAssignment.SOURCE_MANUAL);
        }

        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        coachAssignmentRepository.lockToGroup(coaches.get(0).id(), groups.get(0).id());
        for (int i = 1; i < coaches.size(); i++) {
            coachAssignmentRepository.insert(coaches.get(i).id(), groups.get(i % groups.size()).id(), false, CoachAssignment.SOURCE_MANUAL);
        }
    }

    @Test
    void coldStartNullsUnlockedPlayerAssignmentsButKeepsLockedOnesPinnedWithTheirGroup() {
        String planId = loadPlan();
        scheduleFullyWithOneLockedOfEachKind(planId);

        GroupPlanSolution solution = assembler.assemble(planId, OptimizeSelection.ALL, BlockingOptions.NONE, true).solution();
        List<se.klubb.groupplanner.solver.domain.PlayerAssignment> assignments = solution.getPlayerAssignments();

        List<se.klubb.groupplanner.solver.domain.PlayerAssignment> pinned =
                assignments.stream().filter(se.klubb.groupplanner.solver.domain.PlayerAssignment::isPinned).toList();
        List<se.klubb.groupplanner.solver.domain.PlayerAssignment> unpinned =
                assignments.stream().filter(pa -> !pa.isPinned()).toList();
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).getGroup()).isNotNull();
        assertThat(unpinned).hasSize(assignments.size() - 1).isNotEmpty();
        assertThat(unpinned).allSatisfy(pa -> assertThat(pa.getGroup()).isNull());
    }

    @Test
    void coldStartNullsUnlockedGroupSchedulesButKeepsLockedOnesPinnedWithTheirBlock() {
        String planId = loadPlan();
        scheduleFullyWithOneLockedOfEachKind(planId);

        GroupPlanSolution solution = assembler.assemble(planId, OptimizeSelection.ALL, BlockingOptions.NONE, true).solution();
        List<se.klubb.groupplanner.solver.domain.GroupSchedule> schedules = solution.getGroupSchedules();

        assertThat(schedules).filteredOn(se.klubb.groupplanner.solver.domain.GroupSchedule::isPinned)
                .hasSize(1)
                .allSatisfy(gs -> assertThat(gs.getTrainingBlock()).isNotNull());
        assertThat(schedules).filteredOn(gs -> !gs.isPinned())
                .isNotEmpty()
                .allSatisfy(gs -> assertThat(gs.getTrainingBlock()).isNull());
    }

    @Test
    void coldStartNullsUnlockedCoachSlotsButKeepsLockedOnesPinnedWithTheirCoach() {
        String planId = loadPlan();
        scheduleFullyWithOneLockedOfEachKind(planId);

        GroupPlanSolution solution = assembler.assemble(planId, OptimizeSelection.ALL, BlockingOptions.NONE, true).solution();
        List<se.klubb.groupplanner.solver.domain.CoachSlot> coachSlots = solution.getCoachSlots();

        assertThat(coachSlots).filteredOn(se.klubb.groupplanner.solver.domain.CoachSlot::isPinned)
                .hasSize(1)
                .allSatisfy(cs -> assertThat(cs.getCoach()).isNotNull());
        assertThat(coachSlots).filteredOn(cs -> !cs.isPinned())
                .isNotEmpty()
                .allSatisfy(cs -> assertThat(cs.getCoach()).isNull());
    }

    @Test
    void warmStartDefaultKeepsEveryInitialValueRegardlessOfLock() {
        String planId = loadPlan();
        scheduleFullyWithOneLockedOfEachKind(planId);

        // The pre-existing 3-arg overload (no coldStart param) - must still warm-start every entity,
        // locked or not, exactly like before WI-C.
        GroupPlanSolution solution = assembler.assemble(planId, OptimizeSelection.ALL, BlockingOptions.NONE).solution();

        assertThat(solution.getPlayerAssignments()).allSatisfy(pa -> assertThat(pa.getGroup()).isNotNull());
        assertThat(solution.getGroupSchedules()).allSatisfy(gs -> assertThat(gs.getTrainingBlock()).isNotNull());
        assertThat(solution.getCoachSlots()).allSatisfy(cs -> assertThat(cs.getCoach()).isNotNull());
    }
}
