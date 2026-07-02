package se.klubb.groupplanner.solver.domain;

import se.klubb.groupplanner.common.time.TimeKey;

/**
 * Immutable problem fact for one {@code training_block} row (docs/design/04-solver.md §1.2) — a
 * (time slot, court) pair a {@link GroupSchedule} can be scheduled into.
 *
 * <p>Carries both the resolved {@link TimeKey} (for overlap-based constraints, e.g. two groups
 * clashing at genuinely different times on the same day) AND the raw {@code timeSlotId} (for
 * availability-list membership checks, e.g. {@code PlayerAssignment.canAttend(timeSlotId)} — a
 * participant's "kan inte tider" list is a set of {@code time_slot} ids, not time intervals, so
 * membership must compare ids, not overlap intervals — per this design doc's own note in §1.2).
 */
public record TrainingBlock(
        long id, long courtId, String courtName, TimeKey timeKey, String label, long timeSlotId) {
}
