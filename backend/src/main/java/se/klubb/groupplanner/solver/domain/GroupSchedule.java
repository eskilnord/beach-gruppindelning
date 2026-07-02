package se.klubb.groupplanner.solver.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

/**
 * Planning entity: which {@link TrainingBlock} (time + court) a {@link Group} is scheduled into
 * (docs/design/04-solver.md §1.2). {@code trainingBlock} is deliberately NOT unassignable (§1.4):
 * {@code GroupGenerator} guarantees {@code groupCount <= activeTrainingBlockCount}, so a perfect
 * matching always exists and {@code groupRequiresTrainingBlock} (10.2) is satisfied by construction.
 */
@PlanningEntity
public class GroupSchedule {

    @PlanningId
    private Long id;

    private Group group;

    @PlanningVariable(valueRangeProviderRefs = "trainingBlockRange")
    private TrainingBlock trainingBlock;

    @PlanningPin
    private boolean pinned;

    public GroupSchedule() {
    }

    public GroupSchedule(Long id, Group group, TrainingBlock trainingBlock, boolean pinned) {
        this.id = id;
        this.group = group;
        this.trainingBlock = trainingBlock;
        this.pinned = pinned;
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

    public TrainingBlock getTrainingBlock() {
        return trainingBlock;
    }

    public void setTrainingBlock(TrainingBlock trainingBlock) {
        this.trainingBlock = trainingBlock;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    @Override
    public String toString() {
        return "GroupSchedule{id=" + id + ", group=" + (group == null ? null : group.name()) + "}";
    }
}
