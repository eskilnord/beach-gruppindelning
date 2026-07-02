package se.klubb.groupplanner.resources;

import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.TrainingBlock;

/** A {@link TrainingBlock} enriched with its court's name, for API responses. */
public record TrainingBlockView(
        String id, String timeSlotId, String courtId, String courtName, String activityPlanId, boolean active, boolean locked) {

    public static TrainingBlockView of(TrainingBlock block, Court court) {
        return new TrainingBlockView(
                block.id(), block.timeSlotId(), block.courtId(), court.name(), block.activityPlanId(),
                block.active(), block.locked());
    }
}
