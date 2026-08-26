package se.klubb.groupplanner.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ImportRun;
import se.klubb.groupplanner.domain.ImportTemplate;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.fields.FieldTypes;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ImportRunRepository;
import se.klubb.groupplanner.repo.ImportTemplateRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Transactionally commits an {@link ImportSession} (spec §8.3 step 8 "Importera"): upserts persons
 * per the user's row decisions (§8.7), creates/updates {@code participant_profile} rows (comment ->
 * {@code imported_comment}, spec §8.5) and an initial unassigned {@code player_assignment}, handles
 * the {@code coachName}/{@code isCoach} coach-import targets (docs/plan.md red-team correction),
 * writes {@code custom_field_value}s for {@code customField:} mappings (except {@code timeRelation}
 * targets, which cannot be represented as a raw imported cell value at all - see {@link
 * #TIME_RELATION_IMPORT_WARNING}), records the {@code import_run} audit row, and optionally saves
 * the mapping as a reusable {@code import_template}. Finally recomputes {@code estimatedLevel}/
 * {@code levelConfidence} for the whole plan (docs/plan.md M4 row: "estimatedLevel service ... also
 * auto-run after import commit") — in the same transaction, so a commit and its level recompute
 * always succeed or roll back together.
 *
 * <p>Nothing here ever reads/writes {@code importedComment}/{@code internalNote} for any purpose
 * other than the {@code participant_profile} columns themselves (CLAUDE.md confidentiality rules).
 */
@Service
public class ImportCommitService {

    /**
     * The hidden global custom field used to store a participant's free-text coach wish (docs/plan.md
     * red-team correction: "rows can carry a coach wish"). Created lazily on first use rather than
     * seeded in V2, since it is import-pipeline plumbing, not a spec §9.2 standard field.
     */
    static final String COACH_WISH_FIELD_KEY = "importedCoachWish";

    /**
     * WI-A: {@code timeRelation}-typed fields (the standard {@code canTimes}/{@code cannotTimes}/
     * {@code preferTimes} fields, or any custom field of that type) store a JSON array of {@code
     * time_slot} ids (post-M6a, {@code FieldValueService#validateAndEncode}) - a raw imported cell's
     * free text ("ej 21", "18.00") is a completely different shape and would either fail that
     * validation or, written around it as this importer used to do, silently corrupt the value into
     * something the solver treats as empty (parseIdArray returns [] for a non-array JSON node). So a
     * {@code timeRelation}-mapped column is never written here at all; this warning tells the user
     * where to actually enter it instead.
     */
    static final String TIME_RELATION_IMPORT_WARNING =
            "Tidsönskemål kan inte importeras från Excel – ange dem i spelarvyn efter importen.";

    /** MINOR 10 (B5 review): matches {@code PreviousGroupNormalizer#parseWarningSv}'s exact row-level
     *  message so its rows can be pulled out of the WARN-reason stream and re-aggregated when there
     *  are more than {@link #CANNOT_PARSE_AGGREGATE_THRESHOLD} of them - group 1 captures the quoted
     *  original cell text (MINOR 9: the raw pre-normalization value, not any collapsed/normalized
     *  form) for the aggregate's "t.ex. ..." examples. */
    private static final java.util.regex.Pattern PREVIOUS_GROUP_CANNOT_PARSE_REASON = java.util.regex.Pattern.compile(
            "^Tidigare grupp \"(.*)\" kunde inte tolkas till en gruppnivå.*$");

    /** More than this many rows sharing the cannot-parse condition collapse into one summary warning
     *  instead of one line per row (MINOR 10). */
    private static final int CANNOT_PARSE_AGGREGATE_THRESHOLD = 5;

    private final ImportValidationService importValidationService;
    private final PersonRepository personRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final ImportRunRepository importRunRepository;
    private final ImportTemplateRepository importTemplateRepository;
    private final LevelService levelService;
    private final ObjectMapper objectMapper;

    public ImportCommitService(
            ImportValidationService importValidationService,
            PersonRepository personRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            ImportRunRepository importRunRepository,
            ImportTemplateRepository importTemplateRepository,
            LevelService levelService,
            ObjectMapper objectMapper) {
        this.importValidationService = importValidationService;
        this.personRepository = personRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.importRunRepository = importRunRepository;
        this.importTemplateRepository = importTemplateRepository;
        this.levelService = levelService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CommitResult commit(ImportSession session, String activityPlanId, CommitOptions options) {
        String sheetName = session.selectedSheetOrThrow();
        ParsedSheet sheet = session.sheetOrThrow(sheetName);
        List<ColumnMapping> mappings = session.mappings(sheetName);
        if (mappings.isEmpty()) {
            throw new BadRequestException("No column mapping set for sheet '" + sheetName + "' - call PUT .../mapping first");
        }
        if (options.saveAsTemplate() && (options.templateName() == null || options.templateName().isBlank())) {
            throw new BadRequestException("saveAsTemplate requires a templateName");
        }

        // Re-validate at commit time so decisions are always checked against the current state
        // (mapping/decisions may have changed since the last GET .../validate call).
        List<RowValidationResult> validation = importValidationService.validate(session, activityPlanId);
        BlockStructureDetector.BlockStructure blockStructure = session.blockStructure(sheetName).orElse(null);
        boolean usedBlockGroupMapping = blockStructure != null
                && mappings.stream().anyMatch(m -> m.columnIndex() == ColumnMapping.BLOCK_GROUP_COLUMN_INDEX
                        && m.kind() == MappingTargetKind.PREVIOUS_GROUP_NAME);
        // B5 blank-clears semantics: whether a REAL column (never the synthetic block column) is
        // mapped to previousGroupName - threaded into upsertParticipantProfile so a blank value on a
        // MAPPED REAL column clears the stored value (only the most recent group counts), while
        // leaving the target entirely unmapped still preserves whatever value the profile already has.
        // FIX2 (BLOCKER, B5 review): the synthetic block column deliberately yields no value at all for
        // rows outside any group block (Kölista/waitlist rows, or a block with no confident label) -
        // that is the chooser's "no opinion" on this row, not the file asserting "this person has no
        // previous group". Counting the synthetic column here wiped every waitlisted player's
        // previousGroupName on every re-import whenever it was the (sole) mapped previousGroupName
        // source - see ImportCommitServicePreviousGroupTest#kolistaRowWithOnlySyntheticColumnMappedPreservesExistingPreviousGroupName.
        boolean hasPreviousGroupMapping = mappings.stream().anyMatch(m ->
                m.kind() == MappingTargetKind.PREVIOUS_GROUP_NAME && m.columnIndex() != ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);

        int totalRows = 0;
        int imported = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();
        List<Integer> timeRelationRows = new ArrayList<>();
        // MINOR 10 (B5 review): held back rather than added to `warnings` immediately, so they can be
        // collapsed into ONE aggregate warning when more than 5 rows share the condition (see below) -
        // an unparsable "Tidigare grupp" value is a common, not-actionable-per-row situation on a
        // full-roster import (e.g. a whole "Tränarna"/color-coded group), and 200 identical-shaped
        // warning lines would drown out everything else in the commit result.
        List<String> cannotParseRowLines = new ArrayList<>();
        java.util.LinkedHashSet<String> cannotParseExamples = new java.util.LinkedHashSet<>();
        Map<Integer, RowDecision> decisionsAudit = new LinkedHashMap<>();
        FieldDefinition coachWishField = null;

        for (RowValidationResult result : validation) {
            totalRows++;
            RowDecision decision = session.decision(sheetName, result.rowIndex()).orElseGet(() -> defaultDecisionFor(result));
            decisionsAudit.put(result.rowIndex(), decision);

            if (result.status() == RowStatus.WARN) {
                for (String reason : result.reasons()) {
                    java.util.regex.Matcher cannotParseMatch = PREVIOUS_GROUP_CANNOT_PARSE_REASON.matcher(reason);
                    if (cannotParseMatch.matches()) {
                        cannotParseRowLines.add("Rad " + result.rowIndex() + ": " + reason);
                        cannotParseExamples.add(cannotParseMatch.group(1));
                        continue;
                    }
                    warnings.add("Rad " + result.rowIndex() + ": " + reason);
                }
            }

            if (decision.action() == RowDecision.Action.SKIP) {
                skipped++;
                continue;
            }

            ExtractedRow row = RowExtractor.extract(sheet, result.rowIndex(), mappings, blockStructure);

            Person person = resolvePerson(decision, row);
            person = ensurePersonCapabilities(person, row.isCoach());

            if (row.isCoach()) {
                ensureCoachProfile(person.id(), activityPlanId);
            } else {
                ParticipantProfile profile = upsertParticipantProfile(person.id(), activityPlanId, row, hasPreviousGroupMapping);
                playerAssignmentRepository.insertImportedIfAbsent(profile.id());

                if (ExtractedRow.isNonBlank(row.coachName())) {
                    if (coachWishField == null) {
                        coachWishField = ensureCoachWishField();
                    }
                    writeCustomFieldValue(coachWishField.id(), profile.id(), row.coachName());
                }
                for (Map.Entry<String, String> entry : row.customFieldRaw().entrySet()) {
                    FieldDefinition field = fieldDefinitionRepository.findByKeyVisibleToPlan(activityPlanId, entry.getKey())
                            .orElseThrow(() -> new BadRequestException("Unknown custom field key: " + entry.getKey()));
                    if (FieldTypes.TIME_RELATION.equals(field.fieldType())) {
                        // Do NOT write the raw cell text (see TIME_RELATION_IMPORT_WARNING javadoc) -
                        // skip the value and collect the row for ONE summary warning after the loop
                        // (a per-row warning would repeat the identical message hundreds of times on
                        // a full-roster import).
                        if (timeRelationRows.isEmpty() || timeRelationRows.getLast() != result.rowIndex()) {
                            timeRelationRows.add(result.rowIndex());
                        }
                        continue;
                    }
                    writeCustomFieldValue(field.id(), profile.id(), entry.getValue());
                }
            }

            imported++;
        }

        if (!timeRelationRows.isEmpty()) {
            String prefix;
            if (timeRelationRows.size() == 1) {
                prefix = "Rad " + timeRelationRows.getFirst();
            } else if (timeRelationRows.size() <= 10) {
                StringBuilder rows = new StringBuilder("Rader ");
                for (int i = 0; i < timeRelationRows.size(); i++) {
                    if (i > 0) {
                        rows.append(", ");
                    }
                    rows.append(timeRelationRows.get(i));
                }
                prefix = rows.toString();
            } else {
                prefix = timeRelationRows.size() + " rader";
            }
            warnings.add(prefix + ": " + TIME_RELATION_IMPORT_WARNING);
        }

        // MINOR 10 (B5 review): >5 rows sharing the "cannot parse to a group level" condition collapse
        // into ONE summary warning (with up to 3 distinct example values); <=5 keeps the per-row lines
        // (still individually actionable at that volume).
        if (!cannotParseRowLines.isEmpty()) {
            if (cannotParseRowLines.size() > CANNOT_PARSE_AGGREGATE_THRESHOLD) {
                String examples = cannotParseExamples.stream()
                        .limit(3)
                        .map(example -> "\"" + example + "\"")
                        .collect(java.util.stream.Collectors.joining(", "));
                warnings.add(cannotParseRowLines.size() + " rader har en tidigare grupp som inte kan tolkas till "
                        + "gruppnivå – kontinuitet används inte för dem (t.ex. " + examples + ")");
            } else {
                warnings.addAll(cannotParseRowLines);
            }
        }

        if (usedBlockGroupMapping) {
            warnings.add("Tidigare grupp hämtades från filens gruppstruktur (" + blockStructure.blockCount() + " grupper).");
        }

        ImportRun importRun = recordImportRun(session, activityPlanId, sheetName, totalRows, imported, skipped, validation, decisionsAudit);

        String savedTemplateId = null;
        if (options.saveAsTemplate()) {
            savedTemplateId = saveTemplate(sheet, session.headerRowIndex(sheetName), mappings, options.templateName());
        }

        levelService.recomputeForPlan(activityPlanId);

        return new CommitResult(imported, skipped, warnings, importRun.id(), savedTemplateId);
    }

    private static RowDecision defaultDecisionFor(RowValidationResult result) {
        return result.status() == RowStatus.SKIP ? RowDecision.skip() : RowDecision.createNew();
    }

    private Person resolvePerson(RowDecision decision, ExtractedRow row) {
        if (decision.action() == RowDecision.Action.MATCH_EXISTING) {
            return personRepository.findById(decision.personId())
                    .orElseThrow(() -> new BadRequestException("Unknown personId in decision: " + decision.personId()));
        }
        // A CREATE_NEW row carrying an externalId that already exists must merge onto that person,
        // not insert: person.external_id is UNIQUE, so inserting would roll the whole commit back
        // as an opaque 409 on any re-import of the same members (M3 review finding 1). The member
        // id is the source system's own identity - same id, same person, by definition.
        if (ExtractedRow.isNonBlank(row.externalId())) {
            Optional<Person> byExternalId = personRepository.findByExternalId(row.externalId().strip());
            if (byExternalId.isPresent()) {
                return byExternalId.get();
            }
        }
        Instant now = Instant.now();
        String firstName = ExtractedRow.isNonBlank(row.firstName())
                ? row.firstName()
                : (ExtractedRow.isNonBlank(row.displayName()) ? row.displayName() : "");
        String lastName = ExtractedRow.isNonBlank(row.lastName()) ? row.lastName() : "";
        // Stored stripped so it round-trips exactly with the findByExternalId lookup above.
        String externalId = ExtractedRow.isNonBlank(row.externalId()) ? row.externalId().strip() : null;
        Person person = new Person(
                Uuid7.generate(), firstName, lastName, row.displayName(), row.email(), row.phone(), externalId,
                !row.isCoach(), row.isCoach(), null, now, now);
        return personRepository.insert(person);
    }

    /** Widens an existing person's capability flags if this row needs a capability it doesn't have yet. */
    private Person ensurePersonCapabilities(Person person, boolean isCoach) {
        boolean needsParticipant = !isCoach && !person.canBeParticipant();
        boolean needsCoach = isCoach && !person.canBeCoach();
        if (!needsParticipant && !needsCoach) {
            return person;
        }
        Person updated = new Person(
                person.id(), person.firstName(), person.lastName(), person.displayName(), person.email(), person.phone(),
                person.externalId(), person.canBeParticipant() || needsParticipant, person.canBeCoach() || needsCoach,
                person.notes(), person.createdAt(), Instant.now());
        return personRepository.update(updated);
    }

    private ParticipantProfile upsertParticipantProfile(
            String personId, String activityPlanId, ExtractedRow row, boolean hasPreviousGroupMapping) {
        Double rankingPoints = NumericValue.resolve(row.rankingPointsCell());
        Double previousGroupLevel = NumericValue.resolve(row.previousGroupLevelCell());
        Double manualLevelScore = NumericValue.resolve(row.manualLevelScoreCell());

        Optional<ParticipantProfile> existing =
                participantProfileRepository.findByPersonIdAndActivityPlanId(personId, activityPlanId);
        if (existing.isPresent()) {
            ParticipantProfile e = existing.get();
            // B5 blank-clears semantics: a non-blank imported value always overwrites. A blank value
            // CLEARS the stored previousGroupName when the target is mapped at all (a blank cell on a
            // mapped previous-group column means "no previous group" - only the most recent group
            // should count, per the B1 spec). When the target isn't mapped on this sheet at all, the
            // existing value is left untouched (this row's file never had an opinion on it).
            String previousGroupName = ExtractedRow.isNonBlank(row.previousGroupName())
                    ? row.previousGroupName()
                    : (hasPreviousGroupMapping ? null : e.previousGroupName());
            ParticipantProfile updated = new ParticipantProfile(
                    e.id(), e.personId(), e.activityPlanId(),
                    rankingPoints != null ? rankingPoints : e.rankingPoints(),
                    rankingPoints != null ? "imported" : e.rankingSource(),
                    previousGroupName,
                    previousGroupLevel != null ? previousGroupLevel : e.previousGroupLevel(),
                    e.estimatedLevel(), e.levelConfidence(),
                    manualLevelScore != null ? manualLevelScore : e.manualLevelScore(),
                    ExtractedRow.isNonBlank(row.comment()) ? row.comment() : e.importedComment(),
                    ExtractedRow.isNonBlank(row.internalNote()) ? row.internalNote() : e.internalNote(),
                    e.manualReviewFlag(), e.waitlisted(), e.reviewedDone());
            return participantProfileRepository.update(updated);
        }

        ParticipantProfile created = new ParticipantProfile(
                Uuid7.generate(), personId, activityPlanId,
                rankingPoints, rankingPoints != null ? "imported" : null,
                row.previousGroupName(), previousGroupLevel,
                null, null, manualLevelScore,
                row.comment(), row.internalNote(), false, false, false);
        return participantProfileRepository.insert(created);
    }

    private void ensureCoachProfile(String personId, String activityPlanId) {
        if (coachProfileRepository.findByPersonIdAndActivityPlanId(personId, activityPlanId).isPresent()) {
            return;
        }
        coachProfileRepository.insert(new CoachProfile(
                Uuid7.generate(), personId, activityPlanId, null, null, null, null, null, false, null, false));
    }

    private FieldDefinition ensureCoachWishField() {
        return fieldDefinitionRepository.findGlobalByKey(COACH_WISH_FIELD_KEY)
                .orElseGet(() -> fieldDefinitionRepository.insert(new FieldDefinition(
                        Uuid7.generate(), null, COACH_WISH_FIELD_KEY, "Importerat tränarönskemål", "text",
                        false, "CUSTOM", null, false, "NONE", "INFO", null, null,
                        "Fritext tränarönskemål från import - tolkas inte automatiskt (spec §2.2).", null, null)));
    }

    private void writeCustomFieldValue(String fieldDefinitionId, String participantProfileId, String rawValue) {
        String valueJson;
        try {
            valueJson = objectMapper.writeValueAsString(rawValue);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        customFieldValueRepository.upsert(
                fieldDefinitionId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantProfileId, valueJson);
    }

    private ImportRun recordImportRun(
            ImportSession session,
            String activityPlanId,
            String sheetName,
            int totalRows,
            int imported,
            int skipped,
            List<RowValidationResult> validation,
            Map<Integer, RowDecision> decisionsAudit) {
        String warningsJson;
        String decisionsJson;
        try {
            warningsJson = objectMapper.writeValueAsString(validation);
            decisionsJson = objectMapper.writeValueAsString(decisionsAudit);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String templateId = session.templateMatch(sheetName).map(ImportSession.TemplateMatch::templateId).orElse(null);
        ImportRun run = new ImportRun(
                Uuid7.generate(), activityPlanId, session.fileName(), sheetName, templateId,
                totalRows, imported, skipped, warningsJson, decisionsJson, Instant.now());
        return importRunRepository.insert(run);
    }

    private String saveTemplate(ParsedSheet sheet, int headerRowIndex, List<ColumnMapping> mappings, String templateName) {
        String headerHash = HeaderHash.computeForSheet(sheet, headerRowIndex);
        String mappingJson = ImportTemplateMappingCodec.encode(objectMapper, mappings);
        ImportTemplate template = new ImportTemplate(Uuid7.generate(), templateName, headerHash, mappingJson, Instant.now());
        return importTemplateRepository.insert(template).id();
    }
}
