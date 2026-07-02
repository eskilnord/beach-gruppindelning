package se.klubb.groupplanner.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.exporter.AnonymizedExportService;
import se.klubb.groupplanner.exporter.ExportService;

/**
 * Export endpoints (spec §20, §21.3, docs/design/02-product-data-ui.md §7, M8 task item 2):
 *
 * <ul>
 *   <li>{@code GET /api/plans/{planId}/export?format=xlsx|csv&layout=grouped|flat&
 *       includeComments=false|true&run={runId?}} — the council-facing result export. {@code
 *       includeComments} defaults to {@code false} right here (spec §20.3: "Default ska vara av") —
 *       the caller must pass {@code includeComments=true} explicitly to opt in.</li>
 *   <li>{@code GET /api/plans/{planId}/export/anonymized?format=xlsx|csv} — the open-source-debugging
 *       dataset (spec §21.3), always anonymized, no {@code includeComments} option at all (comments
 *       are never in scope for this endpoint, not even opt-in).</li>
 * </ul>
 *
 * <p>{@code run} is accepted (matching the task's own query-parameter shape) but currently unused —
 * see {@code exporter.ExportDataAssembler}'s javadoc: export warnings are computed from persisted
 * domain data only, not from a specific solve's {@code ScoreAnalysis}, so the export content is
 * identical regardless of which run (or none) is named. A documented, scoped hook for a future
 * per-run cross-reference, not a bug.
 */
@RestController
public class ExportController {

    private final ExportService exportService;
    private final AnonymizedExportService anonymizedExportService;

    public ExportController(ExportService exportService, AnonymizedExportService anonymizedExportService) {
        this.exportService = exportService;
        this.anonymizedExportService = anonymizedExportService;
    }

    @GetMapping("/api/plans/{planId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String planId,
            @RequestParam(required = false, defaultValue = "xlsx") String format,
            @RequestParam(required = false, defaultValue = "grouped") String layout,
            @RequestParam(required = false, defaultValue = "false") boolean includeComments,
            @RequestParam(required = false) String run) {
        ExportService.ExportFile file = exportService.export(planId, format, layout, includeComments);
        return fileResponse(file.bytes(), file.filename(), file.contentType());
    }

    @GetMapping("/api/plans/{planId}/export/anonymized")
    public ResponseEntity<byte[]> exportAnonymized(
            @PathVariable String planId, @RequestParam(required = false, defaultValue = "xlsx") String format) {
        String resolvedFormat = format == null ? "xlsx" : format.toLowerCase(java.util.Locale.ROOT);
        byte[] bytes = anonymizedExportService.export(planId, resolvedFormat);
        boolean csv = "csv".equals(resolvedFormat);
        String contentType = csv ? ExportService.ContentTypes.CSV : ExportService.ContentTypes.XLSX;
        String filename = "anonymiserat." + (csv ? "csv" : "xlsx");
        return fileResponse(bytes, filename, contentType);
    }

    private static ResponseEntity<byte[]> fileResponse(byte[] bytes, String filename, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType(contentType)).body(bytes);
    }
}
