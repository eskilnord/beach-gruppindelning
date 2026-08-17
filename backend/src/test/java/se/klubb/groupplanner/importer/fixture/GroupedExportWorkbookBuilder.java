package se.klubb.groupplanner.importer.fixture;

import java.util.List;
import se.klubb.groupplanner.exporter.ExportData;
import se.klubb.groupplanner.exporter.ExportGroup;
import se.klubb.groupplanner.exporter.ExportPlayer;
import se.klubb.groupplanner.exporter.ExportWaitlistEntry;
import se.klubb.groupplanner.exporter.GroupedXlsxWriter;

/**
 * Builds a small in-memory {@code .xlsx} workbook shaped exactly like this app's own "council
 * layout" export (WP1: {@link BlockStructureDetectorTest}'s Layout 1 fixture) by DELEGATING to the
 * real production {@link GroupedXlsxWriter} - never re-implementing its row layout, so this fixture
 * can never silently drift out of sync with what the export feature actually produces.
 */
public final class GroupedExportWorkbookBuilder {

    public static final String PLAN_NAME = "Torsdagsträning";
    public static final String CATEGORY = "Torsdagsträning";

    private GroupedExportWorkbookBuilder() {
    }

    /** Three groups of three players each, plus a two-entry waitlist. */
    public static byte[] build() {
        ExportData data = new ExportData(PLAN_NAME, CATEGORY, List.of(
                group(1, "Torsdagsträning 1", "18:00", "Anna Andersson", List.of(
                        player("Astrid Svensson", 900.0, 880.0, "Torsdagsträning 1"),
                        player("Bengt Karlsson", 870.0, 860.0, "Torsdagsträning 2"),
                        player("Cecilia Nilsson", 850.0, 840.0, null))),
                group(2, "Torsdagsträning 2", "18:00", "Bo Berg", List.of(
                        player("David Eriksson", 700.0, 690.0, "Torsdagsträning 2"),
                        player("Elsa Larsson", 680.0, 670.0, "Torsdagsträning 1"),
                        player("Filip Olsson", 660.0, 650.0, null))),
                group(3, "Torsdagsträning 3", "19:30", null, List.of(
                        player("Greta Persson", 500.0, 490.0, "Torsdagsträning 3"),
                        player("Hampus Svensson", 480.0, 470.0, null),
                        player("Ida Jonsson", 460.0, 450.0, "Torsdagsträning 3")))),
                List.of(
                        new ExportWaitlistEntry("Jonas Karlsson", 300.0, 290.0, 1),
                        new ExportWaitlistEntry("Klara Hansson", 280.0, 270.0, 2)));
        return new GroupedXlsxWriter().write(data, false);
    }

    private static ExportGroup group(int order, String name, String timeSlotLabel, String coachNames, List<ExportPlayer> players) {
        return new ExportGroup(name, order, timeSlotLabel, null, coachNames, players);
    }

    private static ExportPlayer player(String displayName, double rankingPoints, double estimatedLevel, String previousGroupName) {
        return new ExportPlayer(displayName, rankingPoints, estimatedLevel, previousGroupName, null, List.of());
    }
}
