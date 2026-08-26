package se.klubb.groupplanner.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ImportTemplateRepository;

/**
 * Runs the full import pipeline on an uploaded session so the UI can one-click commit when every
 * automatic decision is confident (sheet, header, mappings), with plain-Swedish reasons for each
 * choice. Mutates the session (header + mappings) so {@code POST .../commit} works immediately
 * when {@link ImportAnalysis#readyToCommit()} is true — and so the step-by-step wizard is
 * pre-filled when it is not.
 */
@Service
public class ImportAnalysisService {

    private static final double SHEET_CONFIDENT = 0.85;
    private static final double COLUMN_CONFIDENT = 0.85;
    private static final String BLOCK_GROUP_COLUMN_HEADER = "Grupp i filen";
    /** Column-A metadata stack header seen in council group files — not player data. */
    private static final Set<String> STRUCTURE_COLUMN_HEADERS = Set.of("grupp", "group");

    private final ActivityPlanRepository activityPlanRepository;
    private final ImportTemplateRepository importTemplateRepository;
    private final ImportValidationService importValidationService;
    private final ObjectMapper objectMapper;

    public ImportAnalysisService(
            ActivityPlanRepository activityPlanRepository,
            ImportTemplateRepository importTemplateRepository,
            ImportValidationService importValidationService,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.importTemplateRepository = importTemplateRepository;
        this.importValidationService = importValidationService;
        this.objectMapper = objectMapper;
    }

    public ImportAnalysis analyzeAndPrepare(ImportSession session, String planId) {
        ActivityPlan plan = activityPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("Activity plan not found: " + planId));

        SheetChoice sheetChoice = chooseSheet(session, plan);
        session.setHeaderRow(sheetChoice.sheetName(), session.headerRowIndex(sheetChoice.sheetName()));

        ParsedSheet sheet = session.sheetOrThrow(sheetChoice.sheetName());
        int headerRowIndex = session.headerRowIndex(sheetChoice.sheetName());

        Optional<ImportSession.TemplateMatch> templateMatch = session.templateMatch(sheetChoice.sheetName());
        Map<Integer, String> templateMapping = templateMatch
                .flatMap(match -> importTemplateRepository.findById(match.templateId()))
                .map(template -> ImportTemplateMappingCodec.decode(objectMapper, template.mappingJson()))
                .orElse(Map.of());
        boolean usedTemplate = !templateMapping.isEmpty();

        Optional<BlockStructureDetector.BlockStructure> blockStructure = session.blockStructure(sheetChoice.sheetName());

        List<ImportAnalysis.ColumnAnalysis> columns = new ArrayList<>();
        List<ColumnMapping> mappings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int mappedCount = 0;
        int ignoredCount = 0;
        boolean anyUnconfident = false;
        boolean anyRealPreviousGroup = false;

        for (int col = 0; col < sheet.columnCount(); col++) {
            String headerText = sheet.cellAt(headerRowIndex, col).rawString();
            ColumnDecision decision = decideColumn(
                    col, headerText, templateMapping, blockStructure.isPresent());
            columns.add(new ImportAnalysis.ColumnAnalysis(
                    col, headerText, decision.target().wireName(), decision.reason(), decision.confidence(), false));
            mappings.add(new ColumnMapping(col, decision.target(), null));
            if (decision.target() == MappingTargetKind.IGNORE) {
                ignoredCount++;
            } else {
                mappedCount++;
                if (decision.target() == MappingTargetKind.PREVIOUS_GROUP_NAME) {
                    anyRealPreviousGroup = true;
                }
            }
            if (decision.confidence() < COLUMN_CONFIDENT) {
                anyUnconfident = true;
                if (headerText != null && !headerText.isBlank() && decision.target() == MappingTargetKind.IGNORE) {
                    warnings.add("Kolumnen \"" + headerText.strip() + "\" kunde inte mappas automatiskt – ignoreras.");
                }
            }
        }

        if (blockStructure.isPresent()) {
            String syntheticTarget = templateMapping.get(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
            MappingTargetKind kind;
            String reason;
            double confidence;
            if (syntheticTarget != null) {
                kind = MappingTargetKind.fromWireName(syntheticTarget.contains(":")
                        ? syntheticTarget.substring(0, syntheticTarget.indexOf(':'))
                        : syntheticTarget);
                if (syntheticTarget.startsWith("customField:")) {
                    // Templates may store customField — analysis keeps ignore for synthetic safety.
                    kind = MappingTargetKind.IGNORE;
                    reason = "Mallens mappning för härledd gruppkolumn kunde inte tillämpas automatiskt";
                    confidence = 0.5;
                    anyUnconfident = true;
                } else {
                    reason = "Från sparad importmall";
                    confidence = 1.0;
                }
            } else if (!anyRealPreviousGroup) {
                kind = MappingTargetKind.PREVIOUS_GROUP_NAME;
                reason = "Härledd från filens gruppblock (" + blockStructure.get().blockCount() + " grupper)";
                confidence = 1.0;
            } else {
                kind = MappingTargetKind.IGNORE;
                reason = "Tidigare grupp finns redan i en vanlig kolumn";
                confidence = 1.0;
            }
            columns.add(new ImportAnalysis.ColumnAnalysis(
                    ColumnMapping.BLOCK_GROUP_COLUMN_INDEX,
                    BLOCK_GROUP_COLUMN_HEADER,
                    kind.wireName(),
                    reason,
                    confidence,
                    true));
            if (kind != MappingTargetKind.IGNORE) {
                mappings.add(new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, kind, null));
                mappedCount++;
            } else {
                ignoredCount++;
            }
        }

        session.setMappings(sheetChoice.sheetName(), mappings);

        List<RowValidationResult> validation = importValidationService.validate(session, planId);
        int ok = 0;
        int warn = 0;
        int skip = 0;
        for (RowValidationResult row : validation) {
            switch (row.status()) {
                case OK -> ok++;
                case WARN -> warn++;
                case SKIP -> skip++;
            }
        }
        int playerRowCount = ok + warn;

        boolean hasNameMapping = mappings.stream().anyMatch(m ->
                m.kind() == MappingTargetKind.DISPLAY_NAME
                        || m.kind() == MappingTargetKind.FIRST_NAME
                        || m.kind() == MappingTargetKind.LAST_NAME);
        if (!hasNameMapping) {
            warnings.add("Ingen namnkolumn kunde mappas automatiskt.");
            anyUnconfident = true;
        }
        if (playerRowCount == 0) {
            warnings.add("Inga spelarader hittades på det valda bladet.");
            anyUnconfident = true;
        }

        boolean readyToCommit = sheetChoice.confidence() >= SHEET_CONFIDENT
                && !anyUnconfident
                && hasNameMapping
                && playerRowCount > 0;

        String templateId = templateMatch.map(ImportSession.TemplateMatch::templateId).orElse(null);
        String templateName = templateMatch.map(ImportSession.TemplateMatch::templateName).orElse(null);

        ImportAnalysis analysis = new ImportAnalysis(
                readyToCommit,
                sheetChoice.sheetName(),
                headerRowIndex,
                sheetChoice.reason(),
                sheetChoice.confidence(),
                usedTemplate,
                templateId,
                templateName,
                List.copyOf(columns),
                mappedCount,
                ignoredCount,
                playerRowCount,
                warn,
                skip,
                List.copyOf(warnings));
        session.setAnalysis(analysis);
        return analysis;
    }

    private ColumnDecision decideColumn(
            int columnIndex,
            String headerText,
            Map<Integer, String> templateMapping,
            boolean hasBlockStructure) {
        String fromTemplate = templateMapping.get(columnIndex);
        if (fromTemplate != null) {
            if (fromTemplate.startsWith("customField:")) {
                return new ColumnDecision(
                        MappingTargetKind.IGNORE,
                        "Mallens anpassade fält (" + fromTemplate + ") kräver manuell granskning",
                        0.5);
            }
            MappingTargetKind kind = MappingTargetKind.fromWireName(fromTemplate);
            return new ColumnDecision(kind, "Från sparad importmall", 1.0);
        }

        String normalized = headerText == null ? "" : ColumnMappingSuggester.normalize(headerText);
        if (hasBlockStructure && STRUCTURE_COLUMN_HEADERS.contains(normalized)) {
            return new ColumnDecision(
                    MappingTargetKind.IGNORE,
                    "Gruppmetadata i filens struktur – spelarnas grupp läses från gruppblocken",
                    1.0);
        }

        Optional<ColumnSuggestion> suggestion = ColumnMappingSuggester.suggestDetailed(headerText);
        if (suggestion.isPresent()) {
            ColumnSuggestion s = suggestion.get();
            return new ColumnDecision(s.kind(), s.reason(), s.confidence());
        }

        if (headerText == null || headerText.isBlank()) {
            return new ColumnDecision(MappingTargetKind.IGNORE, "Tom kolumnrubrik", 1.0);
        }
        return new ColumnDecision(
                MappingTargetKind.IGNORE,
                "Ingen säker mappning för \"" + headerText.strip() + "\"",
                0.4);
    }

    private SheetChoice chooseSheet(ImportSession session, ActivityPlan plan) {
        List<ParsedSheet> sheets = session.workbook().sheets();
        if (sheets.size() == 1) {
            ParsedSheet only = sheets.get(0);
            return new SheetChoice(
                    only.name(),
                    1.0,
                    "Enda bladet i filen (\"" + only.name() + "\")");
        }

        String category = blankToNull(plan.category());
        String planName = blankToNull(plan.name());
        LinkedHashSet<String> needles = new LinkedHashSet<>();
        if (category != null) {
            needles.add(category.toLowerCase(Locale.ROOT));
        }
        if (planName != null) {
            needles.add(planName.toLowerCase(Locale.ROOT));
        }

        ParsedSheet best = null;
        double bestScore = 0.0;
        String bestReason = null;
        for (ParsedSheet sheet : sheets) {
            String sheetLower = sheet.name().toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (sheetLower.equals(needle)) {
                    boolean matchedCategory = category != null
                            && needle.equals(category.toLowerCase(Locale.ROOT));
                    return new SheetChoice(
                            sheet.name(),
                            1.0,
                            "Bladet \"" + sheet.name() + "\" matchar planens "
                                    + (matchedCategory ? "kategori" : "namn"));
                }
                if (sheetLower.contains(needle) || needle.contains(sheetLower)) {
                    double score = 0.9;
                    if (score > bestScore) {
                        bestScore = score;
                        best = sheet;
                        bestReason = "Bladet \"" + sheet.name() + "\" liknar planens kategori/namn";
                    }
                }
            }
        }
        if (best != null) {
            return new SheetChoice(best.name(), bestScore, bestReason);
        }

        // Prefer a sheet that already has a template match.
        for (ParsedSheet sheet : sheets) {
            if (session.templateMatch(sheet.name()).isPresent()) {
                return new SheetChoice(
                        sheet.name(),
                        0.8,
                        "Bladet \"" + sheet.name() + "\" har en sparad importmall");
            }
        }

        ParsedSheet first = sheets.get(0);
        return new SheetChoice(
                first.name(),
                0.5,
                "Första bladet (\"" + first.name() + "\") – kontrollera att rätt blad valts");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record SheetChoice(String sheetName, double confidence, String reason) {
    }

    private record ColumnDecision(MappingTargetKind target, String reason, double confidence) {
    }
}
