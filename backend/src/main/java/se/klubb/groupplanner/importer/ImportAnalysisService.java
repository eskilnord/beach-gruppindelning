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
import se.klubb.groupplanner.groups.PreviousGroupNormalizer;
import se.klubb.groupplanner.groups.PreviousGroupRef;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ImportTemplateRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;

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
    private final TrainingGroupRepository trainingGroupRepository;
    private final ObjectMapper objectMapper;

    public ImportAnalysisService(
            ActivityPlanRepository activityPlanRepository,
            ImportTemplateRepository importTemplateRepository,
            ImportValidationService importValidationService,
            TrainingGroupRepository trainingGroupRepository,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.importTemplateRepository = importTemplateRepository;
        this.importValidationService = importValidationService;
        this.trainingGroupRepository = trainingGroupRepository;
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
        // First real (non-synthetic) column whose decision landed on previousGroupName - captured
        // (not just a boolean) so PreviousGroupColumnChooser can compare it against the synthetic
        // block column below (B5).
        PreviousGroupColumnChooser.Candidate realPreviousGroupCandidate = null;
        boolean realPreviousGroupFromTemplate = false;
        int realPreviousGroupListIndex = -1;
        // FIX3 (MAJOR, B5 review): every OTHER real column that also independently suggested
        // previousGroupName - only the FIRST ever competes against the synthetic block column; every
        // extra one is downgraded to IGNORE outright below (a mapping can never carry two
        // previousGroupName targets, one-click or not).
        List<Integer> extraRealPreviousGroupListIndices = new ArrayList<>();

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
                    if (realPreviousGroupCandidate == null) {
                        realPreviousGroupListIndex = columns.size() - 1;
                        realPreviousGroupFromTemplate = templateMapping.get(col) != null;
                        realPreviousGroupCandidate = new PreviousGroupColumnChooser.Candidate(
                                col, headerText, PreviousGroupColumnChooser.sampleRealColumnValues(
                                        sheet, headerRowIndex, col, blockStructure.orElse(null)));
                    } else {
                        extraRealPreviousGroupListIndices.add(columns.size() - 1);
                    }
                }
            }
            if (decision.confidence() < COLUMN_CONFIDENT) {
                anyUnconfident = true;
                if (headerText != null && !headerText.isBlank() && decision.target() == MappingTargetKind.IGNORE) {
                    warnings.add("Kolumnen \"" + headerText.strip() + "\" kunde inte mappas automatiskt – ignoreras.");
                }
            }
        }

        for (int extraListIndex : extraRealPreviousGroupListIndices) {
            ImportAnalysis.ColumnAnalysis extra = columns.get(extraListIndex);
            columns.set(extraListIndex, new ImportAnalysis.ColumnAnalysis(
                    extra.columnIndex(), extra.headerText(), MappingTargetKind.IGNORE.wireName(),
                    "Endast en kolumn kan mappas till \"Tidigare grupp\" – kolumnen \""
                            + realPreviousGroupCandidate.headerLabel() + "\" användes", 1.0, false));
            mappings.set(extraListIndex, new ColumnMapping(extra.columnIndex(), MappingTargetKind.IGNORE, null));
            mappedCount--;
            ignoredCount++;
        }

        if (blockStructure.isPresent()) {
            String syntheticTarget = templateMapping.get(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
            boolean blockPinnedByTemplate = MappingTargetKind.PREVIOUS_GROUP_NAME.wireName().equals(syntheticTarget);
            if (syntheticTarget != null && syntheticTarget.startsWith("customField:")) {
                // Templates may store customField — analysis keeps ignore for synthetic safety. Not a
                // previousGroupName decision at all, so out of PreviousGroupColumnChooser's scope.
                columns.add(new ImportAnalysis.ColumnAnalysis(
                        ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER, MappingTargetKind.IGNORE.wireName(),
                        "Mallens mappning för härledd gruppkolumn kunde inte tillämpas automatiskt", 0.5, true));
                ignoredCount++;
                anyUnconfident = true;
            } else if (syntheticTarget != null && !blockPinnedByTemplate) {
                // Template pins the synthetic column to something other than previousGroupName -
                // honor it outright, same as any other template pin (unchanged pre-B5 behavior).
                MappingTargetKind kind = MappingTargetKind.fromWireName(syntheticTarget);
                columns.add(new ImportAnalysis.ColumnAnalysis(
                        ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER, kind.wireName(), "Från sparad importmall", 1.0, true));
                if (kind != MappingTargetKind.IGNORE) {
                    mappings.add(new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, kind, null));
                    mappedCount++;
                } else {
                    ignoredCount++;
                }
            } else if (realPreviousGroupCandidate == null) {
                // Only one candidate (the synthetic block column) - it wins by default.
                columns.add(new ImportAnalysis.ColumnAnalysis(
                        ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER,
                        MappingTargetKind.PREVIOUS_GROUP_NAME.wireName(),
                        blockPinnedByTemplate ? "Från sparad importmall"
                                : "Härledd från filens gruppblock (" + blockStructure.get().blockCount() + " grupper)",
                        1.0, true));
                mappings.add(new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, MappingTargetKind.PREVIOUS_GROUP_NAME, null));
                mappedCount++;
            } else {
                // Both a real column and the synthetic block column are candidates - delegate to the
                // shared precedence rule (B5).
                PreviousGroupColumnChooser.Candidate blockCandidate = new PreviousGroupColumnChooser.Candidate(
                        ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER,
                        PreviousGroupColumnChooser.sampleBlockLabels(blockStructure.get()));
                PreviousGroupColumnChooser.Decision chosen = PreviousGroupColumnChooser.choose(
                        realPreviousGroupCandidate, blockCandidate, realPreviousGroupFromTemplate, blockPinnedByTemplate);

                if (chosen.chosenColumnIndex() == ColumnMapping.BLOCK_GROUP_COLUMN_INDEX) {
                    // Block column wins - downgrade the real column's earlier previousGroupName entry
                    // to IGNORE.
                    columns.set(realPreviousGroupListIndex, new ImportAnalysis.ColumnAnalysis(
                            realPreviousGroupCandidate.columnIndex(), realPreviousGroupCandidate.headerLabel(),
                            MappingTargetKind.IGNORE.wireName(), chosen.loserReasonSv(), 1.0, false));
                    mappings.set(realPreviousGroupListIndex,
                            new ColumnMapping(realPreviousGroupCandidate.columnIndex(), MappingTargetKind.IGNORE, null));
                    mappedCount--;
                    ignoredCount++;

                    columns.add(new ImportAnalysis.ColumnAnalysis(
                            ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER,
                            MappingTargetKind.PREVIOUS_GROUP_NAME.wireName(), chosen.chosenReasonSv(), 1.0, true));
                    mappings.add(new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, MappingTargetKind.PREVIOUS_GROUP_NAME, null));
                    mappedCount++;
                } else {
                    // Real column wins (already mapped as previousGroupName above) - the synthetic
                    // column becomes IGNORE.
                    columns.add(new ImportAnalysis.ColumnAnalysis(
                            ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, BLOCK_GROUP_COLUMN_HEADER,
                            MappingTargetKind.IGNORE.wireName(), chosen.loserReasonSv(), 1.0, true));
                    ignoredCount++;
                }
                warnings.add("Tidigare grupp: " + chosen.chosenReasonSv() + ".");
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

        // B5: one aggregate warning (not per-row - would be noisy) when at least one committed-
        // candidate row (OK/WARN, not SKIP) carries a previous-group ordinal higher than the plan's
        // current number of training groups - usually means the file is from a term with more groups
        // than this plan has generated yet, so continuity for those rows may not do what's expected.
        // Skipped entirely when the plan has no groups yet (nothing meaningful to compare against -
        // groups are typically only created after the first import, M5).
        int groupCount = trainingGroupRepository.countByActivityPlanId(planId);
        if (groupCount > 0) {
            int rowsWithHigherOrdinal = 0;
            for (RowValidationResult row : validation) {
                if (row.status() == RowStatus.SKIP) {
                    continue;
                }
                ExtractedRow extracted = RowExtractor.extract(sheet, row.rowIndex(), mappings, blockStructure.orElse(null));
                if (extracted.previousGroupName() == null) {
                    continue;
                }
                PreviousGroupRef ref = PreviousGroupNormalizer.parse(extracted.previousGroupName());
                if (ref != null && ref.groupOrder() != null && ref.groupOrder() > groupCount) {
                    rowsWithHigherOrdinal++;
                }
            }
            if (rowsWithHigherOrdinal > 0) {
                // MINOR 12 (B5 review): trailing period + explicit guidance clause, so the reader knows
                // WHY this matters, not just that a mismatch exists.
                warnings.add(String.format(Locale.ROOT,
                        "%d rader har en tidigare grupp högre än planens antal grupper (%d) – kontinuitet kan inte hålla dem kvar.",
                        rowsWithHigherOrdinal, groupCount));
            }
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

    // MINOR 8: the real-column / block-label sampling helpers used to be duplicated here (near-
    // identically) and in ImportController - both now delegate to the single home on
    // PreviousGroupColumnChooser (sampleRealColumnValues/sampleBlockLabels).

    private record SheetChoice(String sheetName, double confidence, String reason) {
    }

    private record ColumnDecision(MappingTargetKind target, String reason, double confidence) {
    }
}
