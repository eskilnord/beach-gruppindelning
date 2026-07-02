package se.klubb.groupplanner.capacity;

import java.util.List;

/**
 * Pre-solve capacity analysis (spec §12.4) — reproduces the spec's worked example exactly: 122
 * anmälda, 11 active TrainingBlocks, target 10 / max 12 -&gt; targetCapacity 110, maxCapacity 132,
 * status "Möjligt, men grupperna blir större än target".
 *
 * <p>{@code participantCount} is "Antal anmälda" (spec §12.4) — every registered participant,
 * INCLUDING already-waitlisted ones (M5 review fix); {@code waitlistedCount} reports the waitlisted
 * subset separately so the UI can show both.
 *
 * <p>Per-slot breakdown semantics (M5 review fix, aligned with the solver design where CoachFact
 * carries only {@code unavailableTimeSlotIds} — docs/design/04-solver.md): {@code
 * coachesAvailableCount} counts coaches NOT marked {@code UNAVAILABLE} at that slot (neutral/
 * unlisted = available); {@code coachesPreferredCount} is the subset explicitly marked {@code
 * PREFERRED} (a forward signal for M6b's coach-preference soft constraint).
 *
 * <p><b>{@code noCoaches} (v0.2.0, COACH-OPTIONAL SOLVING):</b> {@code coachCount == 0} is a
 * deliberate "no tränare registered yet" state, not a shortage to alarm on with the red {@code
 * coachShortageRisk} styling — a council may genuinely run a plan with no coaches at all (user
 * feedback from the first field test: "om man inte lägger till tränare så ska man ändå kunna
 * optimera grupperna"). When {@code noCoaches} is {@code true}, {@code coachShortageRisk} is forced
 * {@code false} and {@code coachShortageMessage} is repurposed to the neutral, INFO-level "Inga
 * tränare registrerade" instead of the "Risk för tränarbrist: ..." wording — the UI should treat
 * {@code noCoaches} as its own (blue/info) banner state, checked before {@code coachShortageRisk}.
 */
public record CapacityResponse(
        int participantCount,
        int waitlistedCount,
        int activeTrainingBlockCount,
        Integer targetGroupSize,
        Integer maxGroupSize,
        Integer targetCapacity,
        Integer maxCapacity,
        String waitlistRisk,
        String waitlistMessage,
        int coachCount,
        int groupsRequiringCoachEstimate,
        boolean coachShortageRisk,
        String coachShortageMessage,
        boolean noCoaches,
        List<TimeSlotCapacityView> perTimeSlot) {

    /** No configured default sizes -&gt; target/max capacity can't be computed at all. */
    public static final String RISK_UNKNOWN = "UNKNOWN";
    /** Registered participants fit within target capacity. */
    public static final String RISK_NONE = "NONE";
    /** Fits within max capacity but not target — spec §12.4's own worked example. */
    public static final String RISK_OVER_TARGET = "OVER_TARGET";
    /** Exceeds max capacity — a waitlist is required. */
    public static final String RISK_OVER_MAX = "OVER_MAX";

    public record TimeSlotCapacityView(
            String timeSlotId, String label, int activeBlockCount, int coachesAvailableCount, int coachesPreferredCount) {
    }
}
