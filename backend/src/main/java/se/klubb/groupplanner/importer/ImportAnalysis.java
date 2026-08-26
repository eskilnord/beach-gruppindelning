package se.klubb.groupplanner.importer;

import java.util.List;

/**
 * Result of running the full import pipeline (sheet pick, header detect, column mapping, dry-run
 * validation) up front on session creation, so the UI can offer one-click import when every
 * decision is confident — and still show plain-Swedish reasons for each automatic choice.
 */
public record ImportAnalysis(
        boolean readyToCommit,
        String selectedSheet,
        int headerRowIndex,
        String sheetReason,
        double sheetConfidence,
        boolean usedTemplate,
        String templateId,
        String templateName,
        List<ColumnAnalysis> columns,
        int mappedCount,
        int ignoredCount,
        int playerRowCount,
        int warnRowCount,
        int skipRowCount,
        List<String> warnings) {

    public record ColumnAnalysis(
            int columnIndex,
            String headerText,
            String target,
            String reason,
            double confidence,
            boolean synthetic) {
    }
}
