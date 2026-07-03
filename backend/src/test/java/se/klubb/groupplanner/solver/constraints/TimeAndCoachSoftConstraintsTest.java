package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachLevelMismatchJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachPreferredTimeSlotJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachUnknownTimeSlotJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachWishJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.LateTimeJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PairWishSoftJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TimePreferenceMissedJustification;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.WishType;

/** §10.10 timePreferenceSoft, §10.12/10.14 sameGroupSoft/differentGroupSoft, §10.20 coachLevelFit,
 * §10.21a coachPreferenceSoft, §10.22a/b lateTimeForLowerGroups, coachPreferredTimeSlot (M6b new),
 * coachUnknownTimeSlot (WI-B new). */
class TimeAndCoachSoftConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    // ─────────────────────────────────────────────────────────────── §10.10 timePreferenceSoft

    @Test
    void prefersEighteenGetsNineteenThirtyPenalizes() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[] {1L}, g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timePreferenceSoft)
                .given(p, gs)
                .penalizes(1);
    }

    @Test
    void getsPreferredTimeHasNoImpact() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[] {1L}, g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timePreferenceSoft)
                .given(p, gs)
                .hasNoImpact();
    }

    @Test
    void noPreferenceExpressedHasNoImpactEvenIfUnhappy() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timePreferenceSoft)
                .given(p, gs)
                .hasNoImpact();
    }

    @Test
    void justifiesWithTimePreferenceMissedJustification() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[] {1L}, g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timePreferenceSoft)
                .given(p, gs)
                .justifiesWith(new TimePreferenceMissedJustification(1L, 1L, 2L));
    }

    // ────────────────────────────────────────────────────────── §10.12/10.14 pair-wish soft

    @Test
    void wantSameSplitAcrossGroupsPenalizes() {
        Group g1 = group(1);
        Group g2 = group(2);
        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_SAME, 1L, 2L);
        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupSoft)
                .given(wish, player(1, g1), player(2, g2))
                .penalizes(1);
    }

    @Test
    void wantSameTogetherHasNoImpact() {
        Group g1 = group(1);
        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_SAME, 1L, 2L);
        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupSoft)
                .given(wish, player(1, g1), player(2, g1))
                .hasNoImpact();
    }

    @Test
    void justifiesWithPairWishSoftJustificationForSameGroupSoft() {
        Group g1 = group(1);
        Group g2 = group(2);
        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_SAME, 1L, 2L);
        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupSoft)
                .given(wish, player(1, g1), player(2, g2))
                .justifiesWith(new PairWishSoftJustification(1L, 1L, 2L, "WANT_SAME"));
    }

    @Test
    void wantDifferentTogetherPenalizes() {
        Group g1 = group(1);
        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_DIFFERENT, 1L, 2L);
        verifier.verifyThat(GroupPlanConstraintProvider::differentGroupSoft)
                .given(wish, player(1, g1), player(2, g1))
                .penalizes(1);
    }

    @Test
    void wantDifferentSplitHasNoImpact() {
        Group g1 = group(1);
        Group g2 = group(2);
        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_DIFFERENT, 1L, 2L);
        verifier.verifyThat(GroupPlanConstraintProvider::differentGroupSoft)
                .given(wish, player(1, g1), player(2, g2))
                .hasNoImpact();
    }

    // ───────────────────────────────────────────────────────────────── §10.20 coachLevelFit

    @Test
    void coachMaxSixHundredGroupMeanSevenHundredPenalizesByOneHundred() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 60_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        PlayerAssignment p = player(1, 70_000, g);

        verifier.verifyThat(GroupPlanConstraintProvider::coachLevelFit)
                .given(cs, p)
                .penalizesBy(100);
    }

    @Test
    void coachWithinBandHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        PlayerAssignment p = player(1, 70_000, g);

        verifier.verifyThat(GroupPlanConstraintProvider::coachLevelFit)
                .given(cs, p)
                .hasNoImpact();
    }

    @Test
    void justifiesWithCoachLevelMismatchJustification() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 60_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        PlayerAssignment p = player(1, 70_000, g);

        verifier.verifyThat(GroupPlanConstraintProvider::coachLevelFit)
                .given(cs, p)
                .justifiesWith(new CoachLevelMismatchJustification(200L, 1L, 70_000L, 0, 60_000));
    }

    // ─────────────────────────────────────────────────────────────── §10.21a coachPreferenceSoft

    @Test
    void wantedCoachPresentRewards() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        CoachWish wish = new CoachWish(1L, CoachWishType.WANT, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferenceSoft)
                .given(wish, p, cs)
                .rewards(1);
    }

    @Test
    void wantedCoachAbsentHasNoImpact() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachWish wish = new CoachWish(1L, CoachWishType.WANT, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferenceSoft)
                .given(wish, p)
                .hasNoImpact();
    }

    @Test
    void justifiesWithCoachWishJustificationForCoachPreferenceSoft() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        CoachWish wish = new CoachWish(1L, CoachWishType.WANT, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferenceSoft)
                .given(wish, p, cs)
                .justifiesWith(new CoachWishJustification(1L, 200L, "WANT", true));
    }

    // ─────────────────────────────────────────── §10.22a/b lateTimeTopGroups/lateTimeBottomGroups

    @Test
    void topGroupScheduledLatePenalizes() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g1 = group(1);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g1, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeTopGroups)
                .given(gs, policy)
                .penalizes(1);
    }

    @Test
    void topGroupScheduledLateHasNoImpactOnBottomConstraint() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g1 = group(1);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g1, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeBottomGroups)
                .given(gs, policy)
                .hasNoImpact();
    }

    @Test
    void bottomGroupScheduledLateRewards() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g11 = new Group(11, "Grupp 11", 11, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g11, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeBottomGroups)
                .given(gs, policy)
                .rewards(1);
    }

    @Test
    void bottomGroupScheduledLateHasNoImpactOnTopConstraint() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g11 = new Group(11, "Grupp 11", 11, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g11, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeTopGroups)
                .given(gs, policy)
                .hasNoImpact();
    }

    @Test
    void disabledPolicyHasNoImpactOnEitherDirection() {
        LateTimePolicy policy = LateTimePolicy.DISABLED;
        Group g1 = group(1);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g1, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeTopGroups).given(gs, policy).hasNoImpact();
        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeBottomGroups).given(gs, policy).hasNoImpact();
    }

    @Test
    void middleGroupScheduledLateHasNoImpactOnEitherDirection() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g6 = new Group(6, "Grupp 6", 6, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g6, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeTopGroups).given(gs, policy).hasNoImpact();
        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeBottomGroups).given(gs, policy).hasNoImpact();
    }

    @Test
    void justifiesWithLateTimeJustificationForTopGroup() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g1 = group(1);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g1, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeTopGroups)
                .given(gs, policy)
                .justifiesWith(new LateTimeJustification(1L, 1, "21.00", "TOP_LATE_PENALIZED"));
    }

    @Test
    void justifiesWithLateTimeJustificationForBottomGroup() {
        LateTimePolicy policy = new LateTimePolicy(true, 21 * 60, 3, 10);
        Group g11 = new Group(11, "Grupp 11", 11, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock late = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g11, late, false);

        verifier.verifyThat(GroupPlanConstraintProvider::lateTimeBottomGroups)
                .given(gs, policy)
                .justifiesWith(new LateTimeJustification(11L, 11, "21.00", "BOTTOM_LATE_REWARDED"));
    }

    // ───────────────────────────────────────────────────────────── coachPreferredTimeSlot

    @Test
    void coachOnPreferredSlotRewards() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[] {5L}, new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferredTimeSlot)
                .given(cs, gs)
                .rewards(1);
    }

    @Test
    void coachOnUnpreferredSlotHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferredTimeSlot)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void justifiesWithCoachPreferredTimeSlotJustification() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[] {5L}, new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachPreferredTimeSlot)
                .given(cs, gs)
                .justifiesWith(new CoachPreferredTimeSlotJustification(200L, 1L, 5L));
    }

    // ───────────────────────────────────────────────────────────────── coachUnknownTimeSlot (WI-B)

    @Test
    void coachOnUnknownSlotPenalizes() {
        // No explicit AVAILABLE/PREFERRED/UNAVAILABLE row at all for slot 5 - "Okänd".
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .penalizes(1);
    }

    @Test
    void coachOnExplicitlyAvailableSlotHasNoImpact() {
        // AVAILABLE (not PREFERRED) rows fold into availableTimeSlotIds but not preferredTimeSlotIds.
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[] {5L});
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void coachOnPreferredSlotHasNoImpact() {
        // A PREFERRED row folds into BOTH preferredTimeSlotIds and availableTimeSlotIds (assembler
        // fold-down rule) - it is an explicit AVAILABLE-or-better statement, never "Okänd".
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[] {5L}, new long[] {5L});
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void coachOnExplicitlyUnavailableSlotHasNoAdditionalImpact() {
        // Already a HARD violation via coachAvailabilityHard - coachUnknownTimeSlot must not
        // double-punish it.
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[] {5L}, Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void noCoachAssignedHasNoImpact() {
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, null, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void justifiesWithCoachUnknownTimeSlotJustification() {
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachUnknownTimeSlot)
                .given(cs, gs)
                .justifiesWith(new CoachUnknownTimeSlotJustification(200L, 1L, 5L));
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static PlayerAssignment player(long id, Group group) {
        return player(id, 60_000, group);
    }

    private static PlayerAssignment player(long id, int levelScaled, Group group) {
        return new PlayerAssignment(id, id, "P" + id, levelScaled, 3, null, new long[0], new long[0], group, false);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
