package se.klubb.groupplanner.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.groups.PreviousGroupNormalizer;
import se.klubb.groupplanner.importer.match.PersonMatchProposal;
import se.klubb.groupplanner.importer.match.PersonMatcher;
import se.klubb.groupplanner.importer.parse.CellType;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.SwedishTimeParser;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.PersonRepository;

/**
 * Implements every row-level flag from spec §8.6 ("Systemet ska flagga...") plus the §8.7
 * potential-duplicate-vs-existing-person proposals, over the session's currently selected sheet and
 * column mapping.
 */
@Service
public class ImportValidationService {

    /** A field whose values are validated as time expressions when mapped (spec §8.6 "ogiltiga tider"). */
    private static final String TIME_FIELD_TYPE = "timeRelation";

    private final PersonRepository personRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;

    public ImportValidationService(PersonRepository personRepository, FieldDefinitionRepository fieldDefinitionRepository) {
        this.personRepository = personRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
    }

    public List<RowValidationResult> validate(ImportSession session, String activityPlanId) {
        String sheetName = session.selectedSheetOrThrow();
        ParsedSheet sheet = session.sheetOrThrow(sheetName);
        int headerRowIndex = session.headerRowIndex(sheetName);
        List<ColumnMapping> mappings = session.mappings(sheetName);
        if (mappings.isEmpty()) {
            throw new BadRequestException("No column mapping set for sheet '" + sheetName + "' - call PUT .../mapping first");
        }

        boolean hasNameMapping = mappings.stream()
                .anyMatch(m -> m.kind() == MappingTargetKind.FIRST_NAME
                        || m.kind() == MappingTargetKind.LAST_NAME
                        || m.kind() == MappingTargetKind.DISPLAY_NAME);
        boolean hasRankingMapping = mappings.stream().anyMatch(m -> m.kind() == MappingTargetKind.RANKING_POINTS);
        Set<String> timeIshFieldKeys = timeIshCustomFieldKeys(mappings, activityPlanId);
        List<Person> existingPersons = personRepository.findAll();
        BlockStructureDetector.BlockStructure blockStructure = session.blockStructure(sheetName).orElse(null);

        int rowStart = headerRowIndex + 1;
        int rowEnd = sheet.rowCount() - 1;

        Map<Integer, ExtractedRow> extractedByRow = new HashMap<>();
        Map<String, List<Integer>> rowsByNameKey = new HashMap<>();
        Map<String, List<Integer>> rowsByEmailKey = new HashMap<>();
        for (int rowIndex = rowStart; rowIndex <= rowEnd; rowIndex++) {
            if (sheet.isRowEntirelyBlank(rowIndex) || isStructureRow(blockStructure, rowIndex)) {
                continue;
            }
            ExtractedRow extracted = RowExtractor.extract(sheet, rowIndex, mappings, blockStructure);
            extractedByRow.put(rowIndex, extracted);
            if (extracted.hasAnyName()) {
                rowsByNameKey.computeIfAbsent(PersonMatcher.normalizeName(extracted.firstName(), extracted.lastName(), extracted.displayName()),
                        k -> new ArrayList<>()).add(rowIndex);
            }
            if (extracted.email() != null && !extracted.email().isBlank()) {
                rowsByEmailKey.computeIfAbsent(extracted.email().strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rowIndex);
            }
        }

        List<RowValidationResult> results = new ArrayList<>();
        for (int rowIndex = rowStart; rowIndex <= rowEnd; rowIndex++) {
            if (sheet.isRowEntirelyBlank(rowIndex)) {
                results.add(new RowValidationResult(rowIndex, RowStatus.SKIP, List.of("Tom rad"), List.of()));
                continue;
            }
            if (isStructureRow(blockStructure, rowIndex)) {
                results.add(new RowValidationResult(
                        rowIndex, RowStatus.SKIP, List.of("Strukturrad (rubrik/metadata)"), List.of()));
                continue;
            }
            ExtractedRow extracted = extractedByRow.get(rowIndex);
            results.add(validateRow(extracted, hasNameMapping, hasRankingMapping, timeIshFieldKeys,
                    rowsByNameKey, rowsByEmailKey, existingPersons));
        }

        session.setLastValidation(results);
        return results;
    }

    private RowValidationResult validateRow(
            ExtractedRow row,
            boolean hasNameMapping,
            boolean hasRankingMapping,
            Set<String> timeIshFieldKeys,
            Map<String, List<Integer>> rowsByNameKey,
            Map<String, List<Integer>> rowsByEmailKey,
            List<Person> existingPersons) {

        List<String> skipReasons = new ArrayList<>();
        List<String> warnReasons = new ArrayList<>();

        if (hasNameMapping && !row.hasAnyName()) {
            skipReasons.add("Saknar namn");
        }

        String nameKey = PersonMatcher.normalizeName(row.firstName(), row.lastName(), row.displayName());
        if (nameKey != null && rowsByNameKey.getOrDefault(nameKey, List.of()).size() > 1) {
            warnReasons.add("Dubblett i filen (samma namn förekommer på flera rader)");
        }
        if (row.email() != null && !row.email().isBlank()) {
            String emailKey = row.email().strip().toLowerCase(Locale.ROOT);
            if (rowsByEmailKey.getOrDefault(emailKey, List.of()).size() > 1) {
                warnReasons.add("Dubblett i filen (samma e-post förekommer på flera rader)");
            }
        }

        if (hasRankingMapping && row.hasAnyName() && (row.rankingPointsCell() == null || row.rankingPointsCell().isBlank())) {
            warnReasons.add("Saknad ranking");
        }

        checkInvalidNumber(row.rankingPointsCell(), warnReasons);
        checkInvalidNumber(row.previousGroupLevelCell(), warnReasons);
        checkInvalidNumber(row.manualLevelScoreCell(), warnReasons);

        // MINOR 9 (B5 review): quote the ORIGINAL cell text (previousGroupNameRaw), not the already-
        // normalized previousGroupName - ImportedValueNormalizer collapses pipe-separated history down
        // to just the newest segment before this point, which would otherwise make the warning quote
        // something the user never actually typed into this cell.
        String previousGroupWarning = PreviousGroupNormalizer.parseWarningSv(row.previousGroupNameRaw());
        if (previousGroupWarning != null) {
            warnReasons.add(previousGroupWarning);
        }

        for (String timeIshKey : timeIshFieldKeys) {
            ParsedCell cell = row.customFieldCell().get(timeIshKey);
            if (cell != null && !isValidTimeCell(cell)) {
                warnReasons.add("Ogiltig tid - kontrollera manuellt: '" + cell.rawString() + "'");
            }
        }

        List<PersonMatchProposal> matchProposals = PersonMatcher.matchExisting(row, existingPersons);
        if (!matchProposals.isEmpty()) {
            warnReasons.add("Möjlig dubblett av befintlig person");
        }

        List<String> reasons = new ArrayList<>(skipReasons.size() + warnReasons.size());
        reasons.addAll(skipReasons);
        reasons.addAll(warnReasons);

        RowStatus status = !skipReasons.isEmpty() ? RowStatus.SKIP : (!warnReasons.isEmpty() ? RowStatus.WARN : RowStatus.OK);
        return new RowValidationResult(row.rowIndex(), status, reasons, matchProposals);
    }

    /** True when {@code rowIndex} is a heading/metadata/count row of a detected group block (WP1) -
     *  such a row carries no participant and must never reach name/email/etc. validation. */
    private static boolean isStructureRow(BlockStructureDetector.BlockStructure blockStructure, int rowIndex) {
        return blockStructure != null && blockStructure.classByRow().get(rowIndex) == BlockStructureDetector.RowClass.STRUCTURE;
    }

    private static void checkInvalidNumber(ParsedCell cell, List<String> warnReasons) {
        if (cell != null && !cell.isBlank() && NumericValue.resolve(cell) == null) {
            warnReasons.add("Ogiltigt tal: '" + cell.rawString() + "'");
        }
    }

    private static boolean isValidTimeCell(ParsedCell cell) {
        if (cell.isBlank()) {
            return true;
        }
        if (cell.cellType() == CellType.TIME) {
            return true; // A genuine Excel time value is valid by construction.
        }
        return SwedishTimeParser.isValidTimeExpression(cell.rawString());
    }

    /** Custom-field keys mapped for this sheet whose field type is time-related (spec §8.6 "ogiltiga tider"). */
    private Set<String> timeIshCustomFieldKeys(List<ColumnMapping> mappings, String activityPlanId) {
        Set<String> keys = new HashSet<>();
        for (ColumnMapping mapping : mappings) {
            if (mapping.kind() != MappingTargetKind.CUSTOM_FIELD) {
                continue;
            }
            fieldDefinitionRepository.findByKeyVisibleToPlan(activityPlanId, mapping.customFieldKey())
                    .filter(field -> TIME_FIELD_TYPE.equals(field.fieldType()))
                    .ifPresent(field -> keys.add(mapping.customFieldKey()));
        }
        return keys;
    }
}
