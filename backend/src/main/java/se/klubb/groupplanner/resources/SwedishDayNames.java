package se.klubb.groupplanner.resources;

import java.time.DayOfWeek;
import java.util.Map;

/** Swedish display names for {@link DayOfWeek} (spec §6.7 examples: "Torsdag 18.00–19.30"). */
final class SwedishDayNames {

    private static final Map<DayOfWeek, String> NAMES = Map.of(
            DayOfWeek.MONDAY, "Måndag",
            DayOfWeek.TUESDAY, "Tisdag",
            DayOfWeek.WEDNESDAY, "Onsdag",
            DayOfWeek.THURSDAY, "Torsdag",
            DayOfWeek.FRIDAY, "Fredag",
            DayOfWeek.SATURDAY, "Lördag",
            DayOfWeek.SUNDAY, "Söndag");

    private SwedishDayNames() {
    }

    static String of(DayOfWeek dayOfWeek) {
        return NAMES.get(dayOfWeek);
    }
}
