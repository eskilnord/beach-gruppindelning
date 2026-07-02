package se.klubb.groupplanner.exporter;

import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * {@code GET /api/plans/{planId}/export} (spec §20) — orchestrates {@link ExportDataAssembler} +
 * {@link GroupedXlsxWriter}/{@link FlatExporter} into a byte payload + suggested filename +
 * content-type, so {@code api.ExportController} stays a thin HTTP adapter.
 *
 * <p><b>Privacy default (spec §20.3, task M8 item 2):</b> {@code includeComments} has NO implicit
 * default here — the controller must pass an explicit boolean, and {@code
 * api.ExportController}'s {@code @RequestParam(defaultValue = "false")} is where "off by default,
 * explicit opt-in" actually lives, so it is visible at the one place a reviewer would look for it.
 */
@Service
public class ExportService {

    public static final String FORMAT_XLSX = "xlsx";
    public static final String FORMAT_CSV = "csv";
    public static final String LAYOUT_GROUPED = "grouped";
    public static final String LAYOUT_FLAT = "flat";

    private final ExportDataAssembler exportDataAssembler;
    private final GroupedXlsxWriter groupedXlsxWriter;
    private final FlatExporter flatExporter;

    public ExportService(ExportDataAssembler exportDataAssembler, GroupedXlsxWriter groupedXlsxWriter, FlatExporter flatExporter) {
        this.exportDataAssembler = exportDataAssembler;
        this.groupedXlsxWriter = groupedXlsxWriter;
        this.flatExporter = flatExporter;
    }

    public ExportFile export(String activityPlanId, String format, String layout, boolean includeComments) {
        String resolvedFormat = normalize(format, FORMAT_XLSX);
        String resolvedLayout = normalize(layout, LAYOUT_GROUPED);
        if (!FORMAT_XLSX.equals(resolvedFormat) && !FORMAT_CSV.equals(resolvedFormat)) {
            throw new BadRequestException("format must be 'xlsx' or 'csv', was: " + format);
        }
        if (!LAYOUT_GROUPED.equals(resolvedLayout) && !LAYOUT_FLAT.equals(resolvedLayout)) {
            throw new BadRequestException("layout must be 'grouped' or 'flat', was: " + layout);
        }
        if (LAYOUT_GROUPED.equals(resolvedLayout) && FORMAT_CSV.equals(resolvedFormat)) {
            throw new BadRequestException("layout=grouped only supports format=xlsx (a csv has no sheets/blocks) - use layout=flat for csv");
        }

        ExportData data = exportDataAssembler.assemble(activityPlanId, includeComments);
        String baseName = sanitizeFilename(data.planName() != null ? data.planName() : "export");

        if (LAYOUT_GROUPED.equals(resolvedLayout)) {
            return new ExportFile(groupedXlsxWriter.write(data, includeComments), baseName + "_grupper.xlsx", ContentTypes.XLSX);
        }
        if (FORMAT_CSV.equals(resolvedFormat)) {
            return new ExportFile(flatExporter.writeCsv(data, includeComments), baseName + "_export.csv", ContentTypes.CSV);
        }
        return new ExportFile(flatExporter.writeXlsx(data, includeComments), baseName + "_export.xlsx", ContentTypes.XLSX);
    }

    private static String normalize(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.toLowerCase(java.util.Locale.ROOT);
    }

    static String sanitizeFilename(String raw) {
        String cleaned = raw.replaceAll("[^\\p{L}\\p{N}_ -]", "").strip().replace(' ', '_');
        return cleaned.isBlank() ? "export" : cleaned;
    }

    public record ExportFile(byte[] bytes, String filename, String contentType) {
    }

    /** Standard MIME types for the two supported formats (kept as constants so {@code
     * ExportController}/{@code AnonymizedExportService} callers never hand-type the string). */
    public static final class ContentTypes {
        public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        public static final String CSV = "text/csv";

        private ContentTypes() {
        }
    }
}
