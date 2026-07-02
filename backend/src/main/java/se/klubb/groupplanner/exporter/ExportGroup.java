package se.klubb.groupplanner.exporter;

import java.util.List;

/** One training group's export block (spec §20.2, the "council layout" - docs/plan.md Context: a
 * group block stacks {@code name}/{@code groupOrder}/{@code timeSlotLabel}/{@code coachNames}
 * vertically in column A, followed by one row per player). {@code coachNames} is the "; "-joined
 * display names of every coach assigned to the group, or {@code null} if none. */
public record ExportGroup(
        String name,
        Integer groupOrder,
        String timeSlotLabel,
        String courtName,
        String coachNames,
        List<ExportPlayer> players) {
}
