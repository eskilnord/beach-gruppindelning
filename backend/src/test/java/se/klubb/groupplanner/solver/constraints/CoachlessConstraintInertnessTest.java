package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/**
 * v0.2.0 (COACH-OPTIONAL SOLVING): every coach-related constraint must be genuinely inert — zero
 * matches, no exceptions — when the plan has zero {@code CoachSlot} entities and zero {@code
 * CoachWish} facts, which is EXACTLY the state {@code SolverInputAssembler} now produces for a plan
 * with zero {@code coach_profile} rows (see its "CoachSlot entities" section javadoc and
 * backend/docs/v020-notes.md). Every fixture below has a normally-populated non-coach plan (a real
 * group, a real scheduled block, a real placed player) so these assertions prove the coach
 * constraints are inert BECAUSE there is no coach data, not merely because the whole solution is
 * empty.
 */
class CoachlessConstraintInertnessTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class,
            se.klubb.groupplanner.solver.domain.CoachSlot.class);

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 0, 5, 10, 1, 0, 100_000);
    }

    private static TrainingBlock block(long id) {
        return new TrainingBlock(id, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "18.00", id);
    }

    @Test
    void coachNoOverlapIsInertWithZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachNoOverlap).given(p, gs).hasNoImpact();
    }

    @Test
    void coachCannotTrainAndCoachSameTimeIsInertWithZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachCannotTrainAndCoachSameTime).given(p, gs).hasNoImpact();
    }

    @Test
    void coachAvailabilityHardIsInertWithZeroCoachSlots() {
        Group g = group(1);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachAvailabilityHard).given(gs).hasNoImpact();
    }

    /** The critical case: with NO CoachSlot entity at all (not merely one with {@code coach ==
     * null}), {@code forEachIncludingUnassigned(CoachSlot.class)} must yield zero matches — otherwise
     * this is exactly the pre-fix bug (backend/docs/m7-notes.md: "Hard=-12 is coachRequirementHard
     * (no coaches seeded)"). */
    @Test
    void coachRequirementHardIsInertWithZeroCoachSlots() {
        verifier.verifyThat(GroupPlanConstraintProvider::coachRequirementHard).given().hasNoImpact();
    }

    @Test
    void coachMaxGroupsIsInertWithZeroCoachSlots() {
        verifier.verifyThat(GroupPlanConstraintProvider::coachMaxGroups).given().hasNoImpact();
    }

    @Test
    void coachWishRequiredIsInertWithZeroCoachWishesAndZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishRequired).given(p).hasNoImpact();
    }

    @Test
    void coachWishForbiddenIsInertWithZeroCoachWishesAndZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishForbidden).given(p).hasNoImpact();
    }

    @Test
    void coachLevelFitIsInertWithZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachLevelFit).given(p).hasNoImpact();
    }

    @Test
    void coachPreferenceSoftIsInertWithZeroCoachWishesAndZeroCoachSlots() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferenceSoft).given(p).hasNoImpact();
    }

    /** Hardened (v0.2.0 review fast-follow 3): a coach-role PERSON blocking fact IS present and
     * time-overlaps the schedule — the exact shape {@code savedPlanCoachBlocked} exists to penalize
     * — so this proves zero {@code CoachSlot}s beats a live, would-otherwise-match blocking fact,
     * not merely that an empty fact list trivially produces no matches. */
    @Test
    void savedPlanCoachBlockedIsInertWithZeroCoachSlotsEvenWithAPresentBlockingFact() {
        Group g = group(1);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);
        // personId 100 busy at the same Thursday 18.00-19.30 TimeKey the schedule occupies;
        // sourceDetail mirrors the DB row's ROLE_COACH provenance (the solver fact's type is PERSON
        // either way - design §6.2: a saved plan's coaches emit PERSON usages).
        se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage coachUsage =
                new se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage(
                        se.klubb.groupplanner.solver.domain.UsageType.PERSON, 100L, -1L,
                        new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "Låst Herr-plan",
                        se.klubb.groupplanner.domain.SavedPlanResourceUsage.ROLE_COACH);

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanCoachBlocked).given(gs, coachUsage).hasNoImpact();
    }

    @Test
    void coachPreferredTimeSlotIsInertWithZeroCoachSlots() {
        Group g = group(1);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferredTimeSlot).given(gs).hasNoImpact();
    }

    @Test
    void coachUnknownTimeSlotIsInertWithZeroCoachSlots() {
        Group g = group(1);
        GroupSchedule gs = new GroupSchedule(1L, g, block(1L), false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot).given(gs).hasNoImpact();
    }
}
