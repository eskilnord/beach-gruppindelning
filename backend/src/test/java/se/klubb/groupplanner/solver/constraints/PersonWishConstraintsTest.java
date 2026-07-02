package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.WishType;

/** §10.11 sameGroupHard, §10.13 differentGroupHard (docs/design/04-solver.md §4). */
class PersonWishConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void mustSameWishBrokenAcrossTwoGroupsPenalizes() {
        Group g1 = group(1);
        Group g2 = group(2);
        PlayerAssignment a = player(1, g1);
        PlayerAssignment b = player(2, g2);
        PersonPairWish wish = new PersonPairWish(1L, WishType.MUST_SAME, 1L, 2L);

        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupHard)
                .given(wish, a, b)
                .penalizes(1);
    }

    @Test
    void mustSameWishSatisfiedHasNoImpact() {
        Group g1 = group(1);
        PlayerAssignment a = player(1, g1);
        PlayerAssignment b = player(2, g1);
        PersonPairWish wish = new PersonPairWish(1L, WishType.MUST_SAME, 1L, 2L);

        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupHard)
                .given(wish, a, b)
                .hasNoImpact();
    }

    @Test
    void oneUnassignedFriendMeansNoDoublePunishment() {
        // forEach(PlayerAssignment) excludes waitlisted (null-group) entities from the join
        // (design's own note on 10.11) - the waitlist already costs medium, so this must be 0 hard.
        Group g1 = group(1);
        PlayerAssignment a = player(1, g1);
        PlayerAssignment b = player(2, null);
        PersonPairWish wish = new PersonPairWish(1L, WishType.MUST_SAME, 1L, 2L);

        verifier.verifyThat(GroupPlanConstraintProvider::sameGroupHard)
                .given(wish, a, b)
                .hasNoImpact();
    }

    @Test
    void mustDifferentWishBrokenInSameGroupPenalizes() {
        Group g1 = group(1);
        PlayerAssignment a = player(1, g1);
        PlayerAssignment b = player(2, g1);
        PersonPairWish wish = new PersonPairWish(1L, WishType.MUST_DIFFERENT, 1L, 2L);

        verifier.verifyThat(GroupPlanConstraintProvider::differentGroupHard)
                .given(wish, a, b)
                .penalizes(1);
    }

    @Test
    void mustDifferentWishSatisfiedInDifferentGroupsHasNoImpact() {
        Group g1 = group(1);
        Group g2 = group(2);
        PlayerAssignment a = player(1, g1);
        PlayerAssignment b = player(2, g2);
        PersonPairWish wish = new PersonPairWish(1L, WishType.MUST_DIFFERENT, 1L, 2L);

        verifier.verifyThat(GroupPlanConstraintProvider::differentGroupHard)
                .given(wish, a, b)
                .hasNoImpact();
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static PlayerAssignment player(long id, Group group) {
        return new PlayerAssignment(id, id, "P" + id, 60_000, 3, null, new long[0], new long[0], group, false);
    }
}
