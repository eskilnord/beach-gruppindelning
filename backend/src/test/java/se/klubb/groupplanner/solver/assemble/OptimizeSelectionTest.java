package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
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
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * §15.5 "Optimera endast X" (spec §15.5, docs/design/04-solver.md §5 note): class-level pinning via
 * {@link OptimizeSelection}, and the "requires a previously-solved plan" validator.
 */
@SpringBootTest
class OptimizeSelectionTest {

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

    /** Assigns every group/player/coach a real value WITHOUT individually locking it (deliberately
     * NOT using {@code lockToBlock}/{@code lockToGroup}, which also set {@code locked=1}) — so any
     * {@code pinned=true} seen downstream is unambiguously caused by {@link OptimizeSelection}'s
     * class-level pinning, not by a pre-existing individual lock. */
    private void scheduleFully(String planId) {
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        for (int i = 0; i < groups.size(); i++) {
            trainingGroupRepository.updateAssignedTrainingBlock(groups.get(i).id(), blocks.get(i % blocks.size()).id());
        }
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < participants.size(); i++) {
            playerAssignmentRepository.updateGroupAndSource(
                    participants.get(i).id(), groups.get(i % groups.size()).id(),
                    se.klubb.groupplanner.domain.PlayerAssignment.SOURCE_MANUAL);
        }
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < coaches.size(); i++) {
            coachAssignmentRepository.insert(
                    coaches.get(i).id(), groups.get(i % groups.size()).id(), false,
                    se.klubb.groupplanner.domain.CoachAssignment.SOURCE_MANUAL);
        }
    }

    @Test
    void pinningPlayersOnAnUnsolvedPlanIsRejected() {
        String planId = loadPlan(); // fresh: every player_assignment.group_id is still NULL.

        assertThatThrownBy(() -> assembler.assemble(planId, new OptimizeSelection(false, true, true), BlockingOptions.NONE))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("optimize.players");
    }

    @Test
    void pinningScheduleOnAnUnsolvedPlanIsRejected() {
        String planId = loadPlan();

        assertThatThrownBy(() -> assembler.assemble(planId, new OptimizeSelection(true, false, true), BlockingOptions.NONE))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("optimize.schedule");
    }

    @Test
    void pinningCoachesOnAnUnsolvedPlanIsRejected() {
        String planId = loadPlan();

        assertThatThrownBy(() -> assembler.assemble(planId, new OptimizeSelection(true, true, false), BlockingOptions.NONE))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("optimize.coaches");
    }

    @Test
    void pinningPlayersOnAFullySolvedPlanPinsEveryPlayerAssignmentButLeavesScheduleAndCoachesFree() {
        String planId = loadPlan();
        scheduleFully(planId);

        GroupPlanSolution solution = assembler.assemble(planId, new OptimizeSelection(false, true, true), BlockingOptions.NONE).solution();

        assertThat(solution.getPlayerAssignments()).allSatisfy(pa -> assertThat(pa.isPinned()).isTrue());
        assertThat(solution.getGroupSchedules()).anySatisfy(gs -> assertThat(gs.isPinned()).isFalse());
        assertThat(solution.getCoachSlots()).anySatisfy(cs -> assertThat(cs.isPinned()).isFalse());
    }

    @Test
    void pinningScheduleOnAFullySolvedPlanPinsEveryGroupScheduleButLeavesPlayersAndCoachesFree() {
        String planId = loadPlan();
        scheduleFully(planId);

        GroupPlanSolution solution = assembler.assemble(planId, new OptimizeSelection(true, false, true), BlockingOptions.NONE).solution();

        assertThat(solution.getGroupSchedules()).allSatisfy(gs -> assertThat(gs.isPinned()).isTrue());
        assertThat(solution.getPlayerAssignments()).anySatisfy(pa -> assertThat(pa.isPinned()).isFalse());
    }

    @Test
    void pinningCoachesOnAFullySolvedPlanPinsEveryCoachSlotButLeavesPlayersAndScheduleFree() {
        String planId = loadPlan();
        scheduleFully(planId);

        GroupPlanSolution solution = assembler.assemble(planId, new OptimizeSelection(true, true, false), BlockingOptions.NONE).solution();

        assertThat(solution.getCoachSlots()).allSatisfy(cs -> assertThat(cs.isPinned()).isTrue());
        assertThat(solution.getPlayerAssignments()).anySatisfy(pa -> assertThat(pa.isPinned()).isFalse());
    }

    @Test
    void optimizeAllLeavesEverythingUnpinnedRegardlessOfIndividualLocks() {
        String planId = loadPlan();
        scheduleFully(planId);

        GroupPlanSolution solution = assembler.assemble(planId, OptimizeSelection.ALL, BlockingOptions.NONE).solution();

        assertThat(solution.getPlayerAssignments()).allSatisfy(pa -> assertThat(pa.isPinned()).isFalse());
        assertThat(solution.getGroupSchedules()).allSatisfy(gs -> assertThat(gs.isPinned()).isFalse());
        assertThat(solution.getCoachSlots()).allSatisfy(cs -> assertThat(cs.isPinned()).isFalse());
    }
}
