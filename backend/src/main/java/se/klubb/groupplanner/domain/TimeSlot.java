package se.klubb.groupplanner.domain;

/**
 * A recurring or dated training time within one {@link ActivityPlan} (spec §6.7/§7.9), e.g.
 * "Torsdag 18.00–19.30". Exactly one of {@code dayOfWeek} (a {@link java.time.DayOfWeek} name,
 * MONDAY..SUNDAY — for a slot that repeats weekly) or {@code date} (an ISO-8601 date — for a
 * one-off/dated slot) is expected to be set, enforced by {@code TimeSlotController}, not the schema.
 * {@code startTime}/{@code endTime} are {@code "HH:mm"} text; {@code durationMinutes} and {@code
 * label} (when not explicitly overridden by the caller) are always derived from them, never trusted
 * from client input — see {@code se.klubb.groupplanner.resources.TimeSlotLabelFormatter}.
 */
public record TimeSlot(
        String id,
        String activityPlanId,
        String dayOfWeek,
        String date,
        String startTime,
        String endTime,
        Integer durationMinutes,
        String label) {
}
