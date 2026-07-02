package se.klubb.groupplanner.exporter;

import java.util.List;

/** The full export-ready view of one {@code ActivityPlan} (spec §20), assembled by {@link
 * ExportDataAssembler} and consumed by both {@link GroupedXlsxWriter} and {@link FlatExporter}. */
public record ExportData(String planName, String category, List<ExportGroup> groups, List<ExportWaitlistEntry> waitlist) {
}
