package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Exercises every spec §8.6 validation flag (plus §8.7 person matching) against the messy fixture:
 * saknat namn, dubbletter (within file), saknad ranking, ogiltiga tal, tidigare grupp kan inte
 * tolkas, ogiltiga tider, tomma rader, potentiella dubbletter mot befintliga personer.
 */
@SpringBootTest
class ImportValidationServiceIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private ImportValidationService importValidationService;
    @Autowired
    private ImportSessionService importSessionService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private ImportSession uploadFixtureAndMap(MessyWorkbookBuilder.BuiltWorkbook built, String activityPlanId) {
        ImportSessionService.CreatedSession created =
                importSessionService.createSession(activityPlanId, "fixture.xlsx", new ByteArrayInputStream(built.bytes()));
        ImportSession session = importSessionService.get(created.sessionId());
        session.setHeaderRow(MessyWorkbookBuilder.SHEET_NAME, 0);
        session.setMappings(MessyWorkbookBuilder.SHEET_NAME, List.of(
                new ColumnMapping(1, MappingTargetKind.FIRST_NAME, null),
                new ColumnMapping(2, MappingTargetKind.LAST_NAME, null),
                new ColumnMapping(3, MappingTargetKind.RANKING_POINTS, null),
                new ColumnMapping(4, MappingTargetKind.COMMENT, null),
                new ColumnMapping(5, MappingTargetKind.EMAIL, null),
                new ColumnMapping(6, MappingTargetKind.CUSTOM_FIELD, "cannotTimes"),
                new ColumnMapping(7, MappingTargetKind.PREVIOUS_GROUP_NAME, null),
                new ColumnMapping(8, MappingTargetKind.COACH_NAME, null),
                new ColumnMapping(9, MappingTargetKind.IS_COACH, null)));
        return session;
    }

    private static RowValidationResult resultFor(List<RowValidationResult> results, int rowIndex) {
        return results.stream().filter(r -> r.rowIndex() == rowIndex).findFirst()
                .orElseThrow(() -> new AssertionError("No validation result for row " + rowIndex));
    }

    @Test
    void everyValidationFlagFromSpecSection86IsExercised() throws Exception {
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();

        // "potentiella dubbletter mot befintliga personer": an existing person sharing p001's email.
        Instant now = Instant.now();
        personRepository.insert(new Person(
                Uuid7.generate(), "J.", "Johansson (medlemsregister)", null, "johan.johansson1@example.se", null,
                null, true, false, null, now, now));

        ImportSession session = uploadFixtureAndMap(built, planId);
        List<RowValidationResult> results = importValidationService.validate(session, planId);

        // tomma rader.
        assertThat(resultFor(results, built.row("blankRow1")).status()).isEqualTo(RowStatus.SKIP);
        assertThat(resultFor(results, built.row("blankRow1")).reasons()).contains("Tom rad");

        // saknat namn / strukturrad: the group "N spelare" count row and the section heading rows -
        // WP1's BlockStructureDetector now recognizes the messy fixture's column-A metadata stack, so
        // this count row is classified as a structure row (skipped for that reason, not "Saknar namn").
        assertThat(resultFor(results, built.row("group1CountRow")).status()).isEqualTo(RowStatus.SKIP);
        assertThat(resultFor(results, built.row("group1CountRow")).reasons()).contains("Strukturrad (rubrik/metadata)");
        assertThat(resultFor(results, built.row("kolistaHeaderRow")).status()).isEqualTo(RowStatus.SKIP);
        assertThat(resultFor(results, built.row("utanforHeaderRow")).status()).isEqualTo(RowStatus.SKIP);

        // ogiltiga tal: p003's NBSP-thousands rank is valid; p004's "ett tusen" is not.
        RowValidationResult p003 = resultFor(results, built.row("p003"));
        assertThat(p003.reasons()).noneMatch(reason -> reason.startsWith("Ogiltigt tal"));
        RowValidationResult p004 = resultFor(results, built.row("p004"));
        assertThat(p004.status()).isEqualTo(RowStatus.WARN);
        assertThat(p004.reasons()).anyMatch(reason -> reason.startsWith("Ogiltigt tal"));

        // ogiltiga tider: p001's genuine Excel time and p003's "ej 21" are valid; p004's "arton" is not.
        assertThat(p003.reasons()).noneMatch(reason -> reason.startsWith("Ogiltig tid"));
        assertThat(p004.reasons()).anyMatch(reason -> reason.startsWith("Ogiltig tid"));
        RowValidationResult p001 = resultFor(results, built.row("p001"));
        assertThat(p001.reasons()).noneMatch(reason -> reason.startsWith("Ogiltig tid"));

        // saknad ranking: p008's rank column is blank.
        RowValidationResult p008 = resultFor(results, built.row("p008"));
        assertThat(p008.status()).isEqualTo(RowStatus.WARN);
        assertThat(p008.reasons()).contains("Saknad ranking");

        // tidigare grupp kan inte tolkas: both p001 and p003's previous-group text (e.g. "Torsdag Herr
        // 3 (Hösttermin 2025)") parse cleanly to a groupOrder via PreviousGroupNormalizer, so neither
        // is flagged - the check now fires only when the value cannot be parsed to an ordinal at all
        // (see ImportValidationServiceParseWarningTest for that case).
        assertThat(p001.reasons()).noneMatch(reason -> reason.startsWith("Tidigare grupp"));
        assertThat(p003.reasons()).noneMatch(reason -> reason.startsWith("Tidigare grupp"));

        // dubbletter (within file): "p006" and its later re-occurrence share the same normalized name.
        RowValidationResult p006 = resultFor(results, built.row("p006"));
        RowValidationResult p006Duplicate = resultFor(results, built.row("p006Duplicate"));
        assertThat(p006.reasons()).anyMatch(reason -> reason.startsWith("Dubblett i filen"));
        assertThat(p006Duplicate.reasons()).anyMatch(reason -> reason.startsWith("Dubblett i filen"));

        // potentiella dubbletter mot befintliga personer: p001's email matches the seeded person.
        assertThat(p001.matchProposals()).isNotEmpty();
        assertThat(p001.matchProposals().get(0).matchBasis())
                .isEqualTo(se.klubb.groupplanner.importer.match.PersonMatchProposal.MatchBasis.EMAIL_EXACT);
        assertThat(p001.reasons()).anyMatch(reason -> reason.contains("dubblett av befintlig person"));

        // The coach-import row still has a name, so it is not auto-skipped, even though it lacks a
        // ranking (irrelevant for a coach) - status is WARN, not SKIP.
        RowValidationResult coachRow = resultFor(results, built.row("coachRow"));
        assertThat(coachRow.status()).isEqualTo(RowStatus.WARN);
    }

    /**
     * B5: the old "okänd tidigare grupp" check compared the imported value against {@code
     * training_group} names in the DB (deleted in this milestone). The replacement check is purely
     * about parseability - it fires whenever {@link
     * se.klubb.groupplanner.groups.PreviousGroupNormalizer#parse(String)} cannot derive a {@code
     * groupOrder} from the imported value, regardless of whether any training groups exist.
     */
    @Test
    void unparsablePreviousGroupValueIsFlaggedWithTheNewWarning() throws Exception {
        String planId = createPlan();
        ImportSession session = uploadTinyWorkbookAndMap(planId, "Slumpad text utan siffra");

        List<RowValidationResult> results = importValidationService.validate(session, planId);

        RowValidationResult row = results.get(0);
        assertThat(row.status()).isEqualTo(RowStatus.WARN);
        assertThat(row.reasons()).anyMatch(reason -> reason.equals(
                "Tidigare grupp \"Slumpad text utan siffra\" kunde inte tolkas som en grupp — den här deltagaren "
                        + "får ingen fördel av sin tidigare grupp. Du kan fylla i det för hand under Deltagare."));
    }

    @Test
    void parsablePreviousGroupValueIsNotFlagged() throws Exception {
        String planId = createPlan();
        ImportSession session = uploadTinyWorkbookAndMap(planId, "Torsdag Herr 4 (Vårtermin 2026)");

        List<RowValidationResult> results = importValidationService.validate(session, planId);

        RowValidationResult row = results.get(0);
        assertThat(row.reasons()).noneMatch(reason -> reason.startsWith("Tidigare grupp"));
    }

    /** A minimal one-row workbook with just a name column and a "Tidigare grupp" column, for the
     *  parse-warning tests above - the shared {@link MessyWorkbookBuilder} fixture's own
     *  previous-group values all parse cleanly, so it cannot exercise the unparsable-value path. */
    private ImportSession uploadTinyWorkbookAndMap(String planId, String previousGroupValue) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Herr");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Förnamn");
            header.createCell(1).setCellValue("Tidigare grupp");
            org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("Test");
            dataRow.createCell(1).setCellValue(previousGroupValue);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);

            ImportSessionService.CreatedSession created =
                    importSessionService.createSession(planId, "tiny.xlsx", new ByteArrayInputStream(out.toByteArray()));
            ImportSession session = importSessionService.get(created.sessionId());
            session.setHeaderRow("Herr", 0);
            session.setMappings("Herr", List.of(
                    new ColumnMapping(0, MappingTargetKind.FIRST_NAME, null),
                    new ColumnMapping(1, MappingTargetKind.PREVIOUS_GROUP_NAME, null)));
            return session;
        }
    }
}
