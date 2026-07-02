package se.klubb.groupplanner.exporter;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Flat layout (spec §20.2): one row per player, columns {@code Grupp, Tid, Bana, Tränare, Spelare,
 * Ranking, Nivåscore, Tidigare grupp[, Kommentar], Varningar}. Waitlisted participants get their own
 * row too ({@code Grupp="Kölista"}, {@code Tid}/{@code Bana}/{@code Tränare} blank) — otherwise a
 * flat export would silently drop them, unlike the grouped layout's explicit Kölista section.
 *
 * <p>CSV is {@code ;}-delimited UTF-8 with a leading BOM (docs/design/02-product-data-ui.md §7: "opens
 * correctly in Swedish Excel") — see {@link TabularSheetWriter} for the shared byte-level rendering.
 */
@Component
public class FlatExporter {

    public byte[] writeXlsx(ExportData data, boolean includeComments) {
        return TabularSheetWriter.xlsx("Export", flatRows(data, includeComments));
    }

    public byte[] writeCsv(ExportData data, boolean includeComments) {
        return TabularSheetWriter.csv(flatRows(data, includeComments));
    }

    private List<List<String>> flatRows(ExportData data, boolean includeComments) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(headers(includeComments));
        for (ExportGroup group : data.groups()) {
            for (ExportPlayer player : group.players()) {
                rows.add(playerRow(group, player, includeComments));
            }
        }
        for (ExportWaitlistEntry entry : data.waitlist()) {
            rows.add(waitlistRow(entry, includeComments));
        }
        return rows;
    }

    private static List<String> headers(boolean includeComments) {
        List<String> headers = new ArrayList<>(List.of(
                "Grupp", "Tid", "Bana", "Tränare", "Spelare", "Ranking", "Nivåscore", "Tidigare grupp"));
        if (includeComments) {
            headers.add("Kommentar");
        }
        headers.add("Varningar");
        return headers;
    }

    private static List<String> playerRow(ExportGroup group, ExportPlayer player, boolean includeComments) {
        List<String> row = new ArrayList<>(List.of(
                orEmpty(group.name()), orEmpty(group.timeSlotLabel()), orEmpty(group.courtName()),
                orEmpty(group.coachNames()), orEmpty(player.displayName()), numberOrEmpty(player.rankingPoints()),
                numberOrEmpty(player.estimatedLevel()), orEmpty(player.previousGroupName())));
        if (includeComments) {
            row.add(orEmpty(player.comment()));
        }
        row.add(String.join("; ", player.warnings()));
        return row;
    }

    private static List<String> waitlistRow(ExportWaitlistEntry entry, boolean includeComments) {
        List<String> row = new ArrayList<>(List.of(
                "Kölista", "", "", "", orEmpty(entry.displayName()), numberOrEmpty(entry.rankingPoints()),
                numberOrEmpty(entry.estimatedLevel()), ""));
        if (includeComments) {
            row.add("");
        }
        row.add(entry.priority() != null ? "Prioritet " + entry.priority() : "");
        return row;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String numberOrEmpty(Double value) {
        return value == null ? "" : String.valueOf(value);
    }
}
