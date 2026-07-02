package se.klubb.groupplanner.resources;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * Validates {@code TimeSlot} start/end times and derives {@code durationMinutes}/{@code label} when
 * the caller doesn't supply an explicit label (spec §12.1: {@code "Torsdag 18.00–19.30"}).
 *
 * <p>{@code label} auto-generation prefers {@code dayOfWeek} (a recurring slot's natural identity);
 * a dated-only slot derives the Swedish day name from {@code date} instead. Time-of-day is rendered
 * with a "." separator ("18.00", not "18:00" — spec's own examples use the dot), joined with an en
 * dash "–" per the spec text.
 */
@Component
public class TimeSlotLabelFormatter {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** Parses {@code "HH:mm"}, throwing a {@link BadRequestException} with a Swedish-UI-safe message. */
    public LocalTime parseTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(fieldName + " is required (format HH:mm)");
        }
        try {
            return LocalTime.parse(value, HHMM);
        } catch (Exception e) {
            throw new BadRequestException(fieldName + " must be in HH:mm format, was: '" + value + "'");
        }
    }

    public DayOfWeek parseDayOfWeek(String value) {
        try {
            return DayOfWeek.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "dayOfWeek must be one of MONDAY..SUNDAY, was: '" + value + "'");
        }
    }

    public LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw new BadRequestException("date must be an ISO-8601 date (YYYY-MM-DD), was: '" + value + "'");
        }
    }

    /** Canonical {@code "HH:mm"} storage form (normalizes e.g. a hypothetical "9:00" input). */
    public String normalize(LocalTime time) {
        return time.format(HHMM);
    }

    public int durationMinutes(LocalTime start, LocalTime end) {
        int minutes = (end.toSecondOfDay() - start.toSecondOfDay()) / 60;
        if (minutes <= 0) {
            throw new BadRequestException("endTime must be after startTime");
        }
        return minutes;
    }

    /**
     * Auto-generates a label, e.g. "Torsdag 18.00–19.30". Precondition: at least one of {@code
     * dayOfWeek}/{@code date} is non-null (enforced by the caller, spec §7.9).
     */
    public String autoLabel(String dayOfWeek, String date, LocalTime start, LocalTime end) {
        DayOfWeek resolvedDay = dayOfWeek != null ? parseDayOfWeek(dayOfWeek) : parseDate(date).getDayOfWeek();
        String dayName = SwedishDayNames.of(resolvedDay);
        return dayName + " " + toDot(start) + "–" + toDot(end);
    }

    private static String toDot(LocalTime time) {
        return time.format(HHMM).replace(':', '.');
    }
}
