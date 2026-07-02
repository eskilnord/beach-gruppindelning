package se.klubb.groupplanner.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Exhaustive overlap tests for {@link TimeKey} (docs/design/04-solver.md §6.1) — this is the single
 * shared overlap implementation for the solver's schedule-conflict constraints AND the season-wide
 * {@code ConflictService} (M8), so its boundary behaviour must be locked down precisely.
 */
class TimeKeyTest {

    private static final int THURSDAY = 4; // ISO-8601 Monday=1..Sunday=7

    @Test
    void identicalDatelessSlotsOverlap() {
        TimeKey a = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey b = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60 + 30);
        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
    }

    @Test
    void backToBackSlotsThatTouchExactlyDoNotOverlap() {
        TimeKey slot1 = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey slot2 = new TimeKey(TimeKey.NO_DATE, THURSDAY, 19 * 60 + 30, 21 * 60);
        assertThat(slot1.overlaps(slot2)).isFalse();
        assertThat(slot2.overlaps(slot1)).isFalse();
    }

    @Test
    void partiallyOverlappingDatelessSlotsOverlap() {
        TimeKey slot1 = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey slot2 = new TimeKey(TimeKey.NO_DATE, THURSDAY, 19 * 60, 20 * 60);
        assertThat(slot1.overlaps(slot2)).isTrue();
    }

    @Test
    void differentWeekdaysNeverOverlapEvenAtSameTime() {
        TimeKey thursday = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey friday = new TimeKey(TimeKey.NO_DATE, 5, 18 * 60, 19 * 60 + 30);
        assertThat(thursday.overlaps(friday)).isFalse();
    }

    @Test
    void twoConcreteDatesUseDateNotWeekday() {
        // Two different Thursdays (14 days apart -> same ISO weekday) must NOT overlap: concrete
        // dates take priority over dayOfWeek once both sides carry one (§6.1 rule 1).
        int day1 = 20000;
        int day2 = 20000 + 14;
        TimeKey a = new TimeKey(day1, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey b = new TimeKey(day2, THURSDAY, 18 * 60, 19 * 60 + 30);
        assertThat(a.overlaps(b)).isFalse();
    }

    @Test
    void sameConcreteDateOverlappingTimesOverlap() {
        int day = 20000;
        TimeKey a = new TimeKey(day, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey b = new TimeKey(day, THURSDAY, 19 * 60, 20 * 60);
        assertThat(a.overlaps(b)).isTrue();
    }

    @Test
    void oneDatedOneDatelessFallsBackToWeekdayComparison() {
        // §6.1 rule 2: "otherwise (at least one date-less)" -> compare by dayOfWeek.
        TimeKey dated = new TimeKey(20000, THURSDAY, 18 * 60, 19 * 60 + 30);
        TimeKey dateless = new TimeKey(TimeKey.NO_DATE, THURSDAY, 19 * 60, 20 * 60);
        assertThat(dated.overlaps(dateless)).isTrue();

        TimeKey datelessDifferentDay = new TimeKey(TimeKey.NO_DATE, 5, 18 * 60, 19 * 60 + 30);
        assertThat(dated.overlaps(datelessDifferentDay)).isFalse();
    }

    @Test
    void oneMinuteGapDoesNotOverlap() {
        TimeKey a = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60);
        TimeKey b = new TimeKey(TimeKey.NO_DATE, THURSDAY, 19 * 60 + 1, 20 * 60);
        assertThat(a.overlaps(b)).isFalse();
    }

    @Test
    void oneMinuteOverlapDoesOverlap() {
        TimeKey a = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60, 19 * 60);
        TimeKey b = new TimeKey(TimeKey.NO_DATE, THURSDAY, 18 * 60 + 59, 20 * 60);
        assertThat(a.overlaps(b)).isTrue();
    }

    @Test
    void rejectsInvalidDayOfWeek() {
        assertThatThrownBy(() -> new TimeKey(TimeKey.NO_DATE, 0, 60, 120))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeKey(TimeKey.NO_DATE, 8, 60, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new TimeKey(TimeKey.NO_DATE, THURSDAY, 120, 120))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeKey(TimeKey.NO_DATE, THURSDAY, 130, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
