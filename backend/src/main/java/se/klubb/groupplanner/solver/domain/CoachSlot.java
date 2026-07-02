package se.klubb.groupplanner.solver.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

/**
 * Planning entity: which {@link CoachFact} (if any) fills one of a {@link Group}'s required coach
 * slots (docs/design/04-solver.md §1.2). One {@code CoachSlot} exists per {@code
 * 0..requiredCoachCount-1}; {@code requiredCoachCount == 0} means no slots at all for that group.
 * {@code coach} is unassignable (§1.4): a coach shortage must show up as exactly one clean
 * {@code coachRequirementHard} violation, never a forced double-booking.
 *
 * <p>{@code id} is the verifier-corrected synthetic {@code Long} id {@code groupId * 100 +
 * slotIndex} (docs/design/05-solver-verification.md minor finding: the original {@code groupId * 10
 * + slotIndex} collides once {@code slotIndex >= 10}); {@code SolverInputAssembler} validates
 * {@code requiredCoachCount <= 99} so {@code slotIndex} never reaches 100.
 */
@PlanningEntity
public class CoachSlot {

    @PlanningId
    private Long id;

    private Group group;
    private int slotIndex;

    @PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = "coachRange")
    private CoachFact coach;

    @PlanningPin
    private boolean pinned;

    public CoachSlot() {
    }

    public CoachSlot(Long id, Group group, int slotIndex, CoachFact coach, boolean pinned) {
        this.id = id;
        this.group = group;
        this.slotIndex = slotIndex;
        this.coach = coach;
        this.pinned = pinned;
    }

    /** Synthetic id per the verifier-corrected formula; {@code requiredCoachCount} must be &le; 99. */
    public static long syntheticId(long groupId, int slotIndex) {
        return groupId * 100 + slotIndex;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public CoachFact getCoach() {
        return coach;
    }

    public void setCoach(CoachFact coach) {
        this.coach = coach;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    @Override
    public String toString() {
        return "CoachSlot{id=" + id + ", group=" + (group == null ? null : group.name()) + ", slotIndex=" + slotIndex + "}";
    }
}
