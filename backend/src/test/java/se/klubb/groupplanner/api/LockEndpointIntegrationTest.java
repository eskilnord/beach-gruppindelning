package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
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
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * §15.1/§15.2/§15.3 lock endpoints (spec §15, docs/design/04-solver.md §5):
 * {@code PUT|DELETE /api/plans/{planId}/assignments/{participantProfileId}/lock} ({@link
 * AssignmentLockController}), {@code PUT|DELETE /api/groups/{groupId}/lock-block} and {@code
 * .../lock-coach} ({@link GroupController}), including the 409-while-solving guard (design §9.4 /
 * M6b review fix F5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class LockEndpointIntegrationTest {

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
    private TrainingBlockRepository trainingBlockRepository;
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
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private SolveCoordinator solveCoordinator;
    @Autowired
    private SolverInputAssembler solverInputAssembler;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void lockAndUnlockAPlayerAssignment() throws Exception {
        String planId = loadPlan();
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/plans/" + planId + "/assignments/" + participant.id() + "/lock")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + group.id() + "\"}"))
                .andExpect(status().isOk());

        PlayerAssignment locked = playerAssignmentRepository.findByParticipantProfileId(participant.id()).orElseThrow();
        assertThat(locked.locked()).isTrue();
        assertThat(locked.groupId()).isEqualTo(group.id());
        assertThat(locked.source()).isEqualTo(PlayerAssignment.SOURCE_LOCKED);

        mockMvc.perform(delete("/api/plans/" + planId + "/assignments/" + participant.id() + "/lock")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        PlayerAssignment unlocked = playerAssignmentRepository.findByParticipantProfileId(participant.id()).orElseThrow();
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.groupId()).isEqualTo(group.id()); // unlock keeps the current group, just unpins it.
    }

    @Test
    void lockingAPlayerToAGroupOutsideThePlanReturns400() throws Exception {
        String planId = loadPlan();
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/plans/" + planId + "/assignments/" + participant.id() + "/lock")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"does-not-exist\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockingAnUnknownParticipantReturns404() throws Exception {
        String planId = loadPlan();

        mockMvc.perform(put("/api/plans/" + planId + "/assignments/does-not-exist/lock")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"anything\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lockAndUnlockAGroupsTrainingBlock() throws Exception {
        String planId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);
        TrainingBlock block = trainingBlockRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-block")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainingBlockId\":\"" + block.id() + "\"}"))
                .andExpect(status().isOk());

        TrainingGroup locked = trainingGroupRepository.findById(group.id()).orElseThrow();
        assertThat(locked.locked()).isTrue();
        assertThat(locked.assignedTrainingBlockId()).isEqualTo(block.id());

        mockMvc.perform(delete("/api/groups/" + group.id() + "/lock-block").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        TrainingGroup unlocked = trainingGroupRepository.findById(group.id()).orElseThrow();
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.assignedTrainingBlockId()).isEqualTo(block.id());
    }

    @Test
    void lockingAGroupToABlockFromAnotherPlanReturns400() throws Exception {
        String planId = loadPlan();
        String otherPlanId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);
        TrainingBlock foreignBlock = trainingBlockRepository.findByActivityPlanId(otherPlanId).get(0);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-block")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainingBlockId\":\"" + foreignBlock.id() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockAndUnlockACoachToAGroup() throws Exception {
        String planId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);
        CoachProfile coach = coachProfileRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coach.id() + "\"}"))
                .andExpect(status().isOk());

        List<CoachAssignment> assignments = coachAssignmentRepository.findByGroupId(group.id());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).coachProfileId()).isEqualTo(coach.id());
        assertThat(assignments.get(0).locked()).isTrue();

        mockMvc.perform(delete("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .param("coachProfileId", coach.id()))
                .andExpect(status().isNoContent());

        List<CoachAssignment> unlocked = coachAssignmentRepository.findByGroupId(group.id());
        assertThat(unlocked).hasSize(1);
        assertThat(unlocked.get(0).locked()).isFalse();
    }

    /** M6b review fix F5 (design §9.4 "mutating endpoints return 409 while SOLVING_ACTIVE"):
     * a lock flipped mid-solve would be invisible to the running solve's already-assembled snapshot
     * AND could be overwritten by its writeback. One endpoint's 409 path is exercised here; all
     * five other lock/unlock endpoints share the exact same one-line guard ({@code
     * SolveCoordinator.assertNoActiveSolve}). */
    @Test
    void lockingAPlayerWhileASolveIsRunningReturns409() throws Exception {
        String planId = loadPlan();
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0);

        // The THOROUGH (120s) background solve started here is cancelled AND awaited to a
        // terminal RUN ROW status by ActiveSolveCleanup (class-level @ExtendWith) - that
        // extension is the generalization of the inline finally-cleanup this test used to
        // carry (await the run row, not the transient solver status: the cancelled solve's
        // writeback still holds SQLite write locks after NOT_SOLVING flips, which races the
        // next test's inserts and blocks @TempDir deletion on Windows).
        solveCoordinator.startSolve(planId, SolveProfile.THOROUGH);

        mockMvc.perform(put("/api/plans/" + planId + "/assignments/" + participant.id() + "/lock")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + group.id() + "\"}"))
                .andExpect(status().isConflict());

        // The lock was NOT applied - the DB row is untouched.
        PlayerAssignment untouched = playerAssignmentRepository.findByParticipantProfileId(participant.id()).orElseThrow();
        assertThat(untouched.locked()).isFalse();
    }

    /** M9 (found by the M9 review): {@code SolverInputAssembler} only models the first {@code
     * requiredCoachCount} {@code coach_assignment} rows for a group (sorted by id) - a lock beyond
     * that count used to return 200 but be silently ignored by the solver. {@code small-10}'s groups
     * have {@code requiredCoachCount == 1} and the fixture seeds two coaches ({@code c01}/{@code
     * c02}), so this exercises: fill the one slot (A, 200) -> a second coach is refused (B, 409) ->
     * re-locking the SAME coach already in the slot still succeeds (A again, 200, no-op-ish) ->
     * unlocking frees the slot for B (200). */
    @Test
    void lockingASecondCoachBeyondRequiredCoachCountReturns409UntilTheSlotIsFreed() throws Exception {
        String planId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0); // requiredCoachCount == 1
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        CoachProfile coachA = coaches.get(0);
        CoachProfile coachB = coaches.get(1);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coachA.id() + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coachB.id() + "\"}"))
                .andExpect(status().isConflict());

        // Re-locking the coach already occupying the (only) slot is always allowed - not a new row.
        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coachA.id() + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .param("coachProfileId", coachA.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coachB.id() + "\"}"))
                .andExpect(status().isOk());
    }

    /** The original defect's exact precondition (M9 review replay): an UNLOCKED solver-written row
     * already occupies the group's only coach_assignment row when the user locks a DIFFERENT coach.
     * The lock must succeed (locked-count occupancy: 0 locked < requiredCoachCount 1) AND actually
     * reach the solver's modeled slot - {@code SolverInputAssembler} sorts locked rows first, so the
     * new lock (a LATER id than the solver row) still claims slot 0 instead of falling beyond the
     * requiredCoachCount cutoff and being silently ignored. */
    @Test
    void lockingANewCoachOverAnExistingUnlockedSolverRowOccupiesTheModeledSlot() throws Exception {
        String planId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0); // requiredCoachCount == 1
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        CoachProfile coachA = coaches.get(0);
        CoachProfile coachB = coaches.get(1);

        // Stands in for a previous solve's writeback: an unlocked source=solver row for coach A.
        coachAssignmentRepository.insert(coachA.id(), group.id(), false, CoachAssignment.SOURCE_SOLVER);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coachB.id() + "\"}"))
                .andExpect(status().isOk());

        // The lock reached the solver model: the group's single CoachSlot is pinned to coach B.
        AssembledProblem assembled = solverInputAssembler.assemble(planId);
        List<CoachSlot> slotsForGroup = assembled.solution().getCoachSlots().stream()
                .filter(cs -> group.groupOrder() == cs.getGroup().id())
                .toList();
        assertThat(slotsForGroup).hasSize(1);
        CoachSlot slot = slotsForGroup.get(0);
        assertThat(slot.isPinned()).isTrue();
        assertThat(slot.getCoach()).isNotNull();
        assertThat(assembled.coachProfileDbIdByLongId().get(slot.getCoach().coachProfileId())).isEqualTo(coachB.id());
    }

    @Test
    void lockingACoachWithASlotIndexOutOfRangeReturns400() throws Exception {
        String planId = loadPlan();
        TrainingGroup group = trainingGroupRepository.findByActivityPlanId(planId).get(0); // requiredCoachCount == 1
        CoachProfile coach = coachProfileRepository.findByActivityPlanId(planId).get(0);

        mockMvc.perform(put("/api/groups/" + group.id() + "/lock-coach")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachProfileId\":\"" + coach.id() + "\",\"slotIndex\":5}"))
                .andExpect(status().isBadRequest());
    }
}
