package se.klubb.groupplanner.importer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;

/**
 * Server-side wizard state for one in-progress import (spec §8.3): the parsed workbook, the
 * header-row override and column mapping per sheet, per-row decisions, and a validation cache.
 * Nothing here is persisted until {@code ImportCommitService.commit(...)} runs
 * (docs/design/02-product-data-ui.md §2: "nothing persisted until commit").
 *
 * <p>{@code selectedSheet} is set by {@link #setHeaderRow} (spec §8.3 steps 2-4 collapse into one
 * call: choosing a sheet and confirming/overriding its header row) and is what {@code
 * GET .../columns}, {@code PUT .../mapping} (when its own {@code sheet} field is omitted), {@code
 * GET .../validate}, {@code PUT .../decisions}, and {@code POST .../commit} operate on - see
 * backend/docs/m3-notes.md for the full rationale.
 *
 * <p>Not thread-safe beyond {@code synchronized} on the mutable maps below - acceptable for a
 * single-user desktop app talking to itself over localhost (ADR-004's "no write concurrency"
 * rationale applies here too).
 */
public class ImportSession {

    private final String id;
    private final String activityPlanId;
    private final String fileName;
    private final ParsedWorkbook workbook;
    private final Instant createdAt;
    private volatile Instant expiresAt;

    private final Map<String, Integer> headerRowIndexBySheet = new HashMap<>();
    private final Map<String, List<ColumnMapping>> mappingsBySheet = new HashMap<>();
    private final Map<String, TemplateMatch> templateMatchBySheet = new HashMap<>();
    private final Map<Integer, RowDecision> decisions = new HashMap<>();
    private volatile List<RowValidationResult> lastValidation;
    private volatile String selectedSheet;

    public ImportSession(
            String id, String activityPlanId, String fileName, ParsedWorkbook workbook, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.activityPlanId = activityPlanId;
        this.fileName = fileName;
        this.workbook = workbook;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public record TemplateMatch(String templateId, String templateName) {
    }

    public String id() {
        return id;
    }

    /**
     * The plan this session was uploaded for - every plan-scoped endpoint asserts its path
     * {@code planId} matches this, so a session created for plan A can never validate/commit into
     * plan B (M3 review finding 4).
     */
    public String activityPlanId() {
        return activityPlanId;
    }

    public String fileName() {
        return fileName;
    }

    public ParsedWorkbook workbook() {
        return workbook;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void renewExpiry(Instant newExpiresAt) {
        this.expiresAt = newExpiresAt;
    }

    public ParsedSheet sheetOrThrow(String sheetName) {
        return workbook.sheetByName(sheetName)
                .orElseThrow(() -> new BadRequestException("Unknown sheet: " + sheetName));
    }

    /** The header row index for a sheet: an explicit override if set, else the heuristic default. */
    public synchronized int headerRowIndex(String sheetName) {
        return headerRowIndexBySheet.computeIfAbsent(sheetName, name -> HeaderDetector.detect(sheetOrThrow(name)));
    }

    public synchronized void setHeaderRow(String sheetName, int headerRowIndex) {
        sheetOrThrow(sheetName);
        headerRowIndexBySheet.put(sheetName, headerRowIndex);
        this.selectedSheet = sheetName;
    }

    public synchronized void setTemplateMatch(String sheetName, TemplateMatch match) {
        templateMatchBySheet.put(sheetName, match);
    }

    public synchronized Optional<TemplateMatch> templateMatch(String sheetName) {
        return Optional.ofNullable(templateMatchBySheet.get(sheetName));
    }

    public String selectedSheetOrThrow() {
        String sheet = selectedSheet;
        if (sheet == null) {
            throw new BadRequestException("No sheet selected yet - call PUT .../header first");
        }
        return sheet;
    }

    public synchronized void setMappings(String sheetName, List<ColumnMapping> mappings) {
        sheetOrThrow(sheetName);
        mappingsBySheet.put(sheetName, List.copyOf(mappings));
        this.selectedSheet = sheetName;
        this.lastValidation = null; // Mapping changed - any cached validation is stale.
    }

    public synchronized List<ColumnMapping> mappings(String sheetName) {
        return mappingsBySheet.getOrDefault(sheetName, List.of());
    }

    public synchronized void setDecision(int rowIndex, RowDecision decision) {
        decisions.put(rowIndex, decision);
    }

    public synchronized Optional<RowDecision> decision(int rowIndex) {
        return Optional.ofNullable(decisions.get(rowIndex));
    }

    public synchronized void setLastValidation(List<RowValidationResult> results) {
        this.lastValidation = new ArrayList<>(results);
    }

    public List<RowValidationResult> lastValidation() {
        List<RowValidationResult> results = lastValidation;
        return results == null ? null : List.copyOf(results);
    }
}
