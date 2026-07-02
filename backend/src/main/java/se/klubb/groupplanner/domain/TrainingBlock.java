package se.klubb.groupplanner.domain;

/**
 * {@code TrainingBlock = TimeSlot + Court} (spec §6.9/§7.10) — the unit a {@code training_group} is
 * assigned to. Rows are never deleted by the regeneration flow (only deactivated) so identity
 * (and therefore any later group assignment referencing it) survives a court-count change —
 * {@code UNIQUE(time_slot_id, court_id)} in the schema is what makes regeneration idempotent.
 *
 * <p>{@code deactivationSource} records WHY an inactive block is inactive ({@code null} while
 * active): {@link #DEACTIVATION_MANUAL} (a §12.3 manual exception via {@code PATCH
 * /api/training-blocks/{id}} — must survive regeneration) or {@link #DEACTIVATION_SHRINK}
 * (deactivated because the slot's declared court count shrank — automatically reactivated when the
 * count grows back).
 */
public record TrainingBlock(
        String id,
        String timeSlotId,
        String courtId,
        String activityPlanId,
        boolean active,
        boolean locked,
        String deactivationSource) {

    /** Deactivated by the user via {@code PATCH /api/training-blocks/{id}} (spec §12.3). */
    public static final String DEACTIVATION_MANUAL = "MANUAL";
    /** Deactivated because the slot's declared court count shrank below this block's court index. */
    public static final String DEACTIVATION_SHRINK = "SHRINK";
}
