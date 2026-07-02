package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.constraints.Justifications.UnassignedPlayerJustification;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/** unassignedPlayer (MEDIUM, docs/design/04-solver.md §2.1) — the reserved waitlist penalty. */
class UnassignedPlayerConstraintTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void unassignedPlayerPenalizedByPriorityAsMatchWeight() {
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], null, false);

        verifier.verifyThat(GroupPlanConstraintProvider::unassignedPlayer)
                .given(p)
                .penalizesBy(3);
    }

    @Test
    void higherPriorityUnassignedPlayerPenalizesMore() {
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 5, null, new long[0], new long[0], null, false);

        verifier.verifyThat(GroupPlanConstraintProvider::unassignedPlayer)
                .given(p)
                .penalizesBy(5);
    }

    @Test
    void assignedPlayerHasNoImpact() {
        Group g = new Group(1L, "Grupp 1", 1, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::unassignedPlayer)
                .given(p)
                .hasNoImpact();
    }

    @Test
    void justifiesWithUnassignedPlayerJustificationCarryingParticipantAndPriority() {
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], null, false);

        verifier.verifyThat(GroupPlanConstraintProvider::unassignedPlayer)
                .given(p)
                .justifiesWith(new UnassignedPlayerJustification(1L, "Kalle", 3));
    }
}
