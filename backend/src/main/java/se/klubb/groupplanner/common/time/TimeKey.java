package se.klubb.groupplanner.common.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A pure-integer, deterministic representation of "when" a {@code TrainingBlock} or a resource
 * usage happens (docs/design/04-solver.md §6.1), shared between the solver (time-availability/
 * overlap constraints) and the season-wide conflict reporting.
 *
 * <p>{@code epochDay} is {@link java.time.LocalDate#toEpochDay()} for a dated slot, or {@code -1}
 * for a date-less (recurring weekday) slot. {@code dayOfWeek} is ISO-8601 (Monday=1..Sunday=7).
 * {@code startMinuteOfDay}/{@code endMinuteOfDay} are minutes since midnight ({@code 0..1440}).
 *
 * <p>No float/double anywhere (CLAUDE.md determinism rules) — this type lives outside {@code
 * solver.domain}/{@code solver.constraints} but is used by both, so it is held to the same integer
 * discipline defensively.
 */
public record TimeKey(int epochDay, int dayOfWeek, int startMinuteOfDay, int endMinuteOfDay) {

    /** Sentinel for a date-less (recurring weekday) slot. */
    public static final int NO_DATE = -1;

    public TimeKey {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("dayOfWeek must be ISO 1..7, got " + dayOfWeek);
        }
        if (startMinuteOfDay < 0 || startMinuteOfDay > 1440 || endMinuteOfDay < 0 || endMinuteOfDay > 1440) {
            throw new IllegalArgumentException("minute-of-day must be within 0..1440");
        }
        if (startMinuteOfDay >= endMinuteOfDay) {
            throw new IllegalArgumentException("startMinuteOfDay must be strictly before endMinuteOfDay");
        }
    }

    /**
     * Deterministic pure-int overlap (docs/design/04-solver.md §6.1):
     *
     * <ol>
     *   <li>Both concrete dates ({@code epochDay >= 0}): overlap iff same {@code epochDay} AND the
     *       half-open minute intervals intersect.
     *   <li>Otherwise (at least one date-less, recurring-weekday slot): overlap iff same {@code
     *       dayOfWeek} AND the half-open minute intervals intersect.
     * </ol>
     *
     * <p>Interval intersection is the standard half-open-interval test ({@code startA < endB &&
     * startB < endA}) so two back-to-back slots that touch exactly (19.30 end / 19.30 start) do
     * NOT overlap.
     */
    public boolean overlaps(TimeKey other) {
        boolean sameDay = (epochDay >= 0 && other.epochDay >= 0)
                ? epochDay == other.epochDay
                : dayOfWeek == other.dayOfWeek;
        if (!sameDay) {
            return false;
        }
        return startMinuteOfDay < other.endMinuteOfDay && other.startMinuteOfDay < endMinuteOfDay;
    }

    /**
     * Shared factory from the raw text fields every {@code TimeSlot}-shaped row stores (day-of-week
     * name OR ISO date, plus {@code "HH:mm"} start/end) — used by {@code SolverInputAssembler}, the
     * cross-plan blocking usage assembly, and {@code se.klubb.groupplanner.season.ConflictService},
     * so every caller builds a {@code TimeKey} identically (docs/design/04-solver.md §6.1: "single
     * implementation"). Exactly one of {@code dayOfWeek}/{@code date} must be non-null.
     */
    public static TimeKey of(String dayOfWeek, String date, String startTime, String endTime) {
        int epochDay = NO_DATE;
        int dow;
        if (date != null) {
            LocalDate d = LocalDate.parse(date);
            epochDay = (int) d.toEpochDay();
            dow = d.getDayOfWeek().getValue();
        } else {
            dow = DayOfWeek.valueOf(dayOfWeek).getValue();
        }
        return new TimeKey(epochDay, dow, minuteOfDay(startTime), minuteOfDay(endTime));
    }

    private static int minuteOfDay(String hhmm) {
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }
}
