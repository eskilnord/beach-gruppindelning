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
 * writes {@code custom_field_value}s for {@code customField:} mappings, records the {@code
 * import_run} audit row, and optionally saves the mapping as a reusable {@code import_template}.
 * Finally recomputes {@code estimatedLevel}/{@code levelConfidence} for the whole plan (docs/plan.md
 * M4 row: "estimatedLevel service ... also auto-run after import commit") — in the same transaction,
 * so a commit and its level recompute always succeed or roll back together.
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

        int totalRows = 0;
        int imported = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();
        Map<Integer, RowDecision> decisionsAudit = new LinkedHashMap<>();
        FieldDefinition coachWishField = null;

        for (RowValidationResult result : validation) {
            totalRows++;
            RowDecision decision = session.decision(result.rowIndex()).orElseGet(() -> defaultDecisionFor(result));
            decisionsAudit.put(result.rowIndex(), decision);

            if (result.status() == RowStatus.WARN) {
                for (String reason : result.reasons()) {
                    warnings.add("Rad " + result.rowIndex() + ": " + reason);
                }
            }

            if (decision.action() == RowDecision.Action.SKIP) {
                skipped++;
                continue;
            }

            ExtractedRow row = RowExtractor.extract(sheet, result.rowIndex(), mappings);

            Person person = resolvePerson(decision, row);
            person = ensurePersonCapabilities(person, row.isCoach());

            if (row.isCoach()) {
                ensureCoachProfile(person.id(), activityPlanId);
            } else {
                ParticipantProfile profile = upsertParticipantProfile(person.id(), activityPlanId, row);
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
                    writeCustomFieldValue(field.id(), profile.id(), entry.getValue());
                }
            }

            imported++;
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

    private ParticipantProfile upsertParticipantProfile(String personId, String activityPlanId, ExtractedRow row) {
        Double rankingPoints = NumericValue.resolve(row.rankingPointsCell());
        Double previousGroupLevel = NumericValue.resolve(row.previousGroupLevelCell());
        Double manualLevelScore = NumericValue.resolve(row.manualLevelScoreCell());

        Optional<ParticipantProfile> existing =
                participantProfileRepository.findByPersonIdAndActivityPlanId(personId, activityPlanId);
        if (existing.isPresent()) {
            ParticipantProfile e = existing.get();
            ParticipantProfile updated = new ParticipantProfile(
                    e.id(), e.personId(), e.activityPlanId(),
                    rankingPoints != null ? rankingPoints : e.rankingPoints(),
                    rankingPoints != null ? "imported" : e.rankingSource(),
                    ExtractedRow.isNonBlank(row.previousGroupName()) ? row.previousGroupName() : e.previousGroupName(),
                    previousGroupLevel != null ? previousGroupLevel : e.previousGroupLevel(),
                    e.estimatedLevel(), e.levelConfidence(),
                    manualLevelScore != null ? manualLevelScore : e.manualLevelScore(),
                    ExtractedRow.isNonBlank(row.comment()) ? row.comment() : e.importedComment(),
                    ExtractedRow.isNonBlank(row.internalNote()) ? row.internalNote() : e.internalNote(),
                    e.manualReviewFlag(), e.waitlisted());
            return participantProfileRepository.update(updated);
        }

        ParticipantProfile created = new ParticipantProfile(
                Uuid7.generate(), personId, activityPlanId,
                rankingPoints, rankingPoints != null ? "imported" : null,
                row.previousGroupName(), previousGroupLevel,
                null, null, manualLevelScore,
                row.comment(), row.internalNote(), false, false);
        return participantProfileRepository.insert(created);
    }

    private void ensureCoachProfile(String personId, String activityPlanId) {
        if (coachProfileRepository.findByPersonIdAndActivityPlanId(personId, activityPlanId).isPresent()) {
            return;
        }
        coachProfileRepository.insert(new CoachProfile(
                Uuid7.generate(), personId, activityPlanId, null, null, null, null, null, false, null));
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
