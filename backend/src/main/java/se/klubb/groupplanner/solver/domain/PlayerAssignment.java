package se.klubb.groupplanner.solver.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import java.util.Arrays;

/**
 * Planning entity: which {@link Group} (if any) a participant is placed into
 * (docs/design/04-solver.md §1.2). {@code group == null} is the "Oplacerad/Kölista" (waitlist)
 * state — a first-class, never-hard-violating outcome (§2).
 *
 * <p>Every field except {@code group}/{@code pinned} is a problem fact folded directly into this
 * entity (design's own choice, §1.2: "problem facts folded in (immutable during solve)") rather than
 * split into a separate {@code ParticipantFact} type.
 *
 * <p>{@code id} is NOT the DB's UUIDv7 {@code participant_profile.id} — see {@code
 * SolverInputAssembler}'s id-mapping note for why every solver-domain id in this package is a
 * deterministic {@code long}/{@code Long} derived from sorted DB ids rather than the DB id itself
 * (this design doc assumed numeric DB ids; the actual schema uses TEXT UUIDv7 PKs).
 */
@PlanningEntity
public class PlayerAssignment {

    @PlanningId
    private Long id;

    private long personId;
    private String displayName;
    private int levelScaled;
    private int priority;
    private Integer previousGroupOrder;
    private long[] unavailableTimeSlotIds;
    private long[] preferredTimeSlotIds;

    @PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = "groupRange")
    private Group group;

    @PlanningPin
    private boolean pinned;

    /** No-arg constructor required by Timefold's reflective domain access. */
    public PlayerAssignment() {
    }

    public PlayerAssignment(
            Long id,
            long personId,
            String displayName,
            int levelScaled,
            int priority,
            Integer previousGroupOrder,
            long[] unavailableTimeSlotIds,
            long[] preferredTimeSlotIds,
            Group group,
            boolean pinned) {
        this.id = id;
        this.personId = personId;
        this.displayName = displayName;
        this.levelScaled = levelScaled;
        this.priority = priority;
        this.previousGroupOrder = previousGroupOrder;
        this.unavailableTimeSlotIds = unavailableTimeSlotIds;
        this.preferredTimeSlotIds = preferredTimeSlotIds;
        this.group = group;
        this.pinned = pinned;
    }

    /** Binary search over the sorted {@code unavailableTimeSlotIds} array (§1.2). */
    public boolean canAttend(long timeSlotId) {
        return Arrays.binarySearch(unavailableTimeSlotIds, timeSlotId) < 0;
    }

    public boolean hasPreferences() {
        return preferredTimeSlotIds.length > 0;
    }

    public boolean prefers(long timeSlotId) {
        return Arrays.binarySearch(preferredTimeSlotIds, timeSlotId) >= 0;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getPersonId() {
        return personId;
    }

    public void setPersonId(long personId) {
        this.personId = personId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getLevelScaled() {
        return levelScaled;
    }

    public void setLevelScaled(int levelScaled) {
        this.levelScaled = levelScaled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Integer getPreviousGroupOrder() {
        return previousGroupOrder;
    }

    public void setPreviousGroupOrder(Integer previousGroupOrder) {
        this.previousGroupOrder = previousGroupOrder;
    }

    public long[] getUnavailableTimeSlotIds() {
        return unavailableTimeSlotIds;
    }

    public void setUnavailableTimeSlotIds(long[] unavailableTimeSlotIds) {
        this.unavailableTimeSlotIds = unavailableTimeSlotIds;
    }

    public long[] getPreferredTimeSlotIds() {
        return preferredTimeSlotIds;
    }

    public void setPreferredTimeSlotIds(long[] preferredTimeSlotIds) {
        this.preferredTimeSlotIds = preferredTimeSlotIds;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    @Override
    public String toString() {
        return "PlayerAssignment{id=" + id + ", displayName='" + displayName + "'}";
    }
}
