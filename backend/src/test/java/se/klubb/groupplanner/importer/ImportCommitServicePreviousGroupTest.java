package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.importer.fixture.GroupedExportWorkbookBuilder;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * B5 blank-clears semantics for {@code previous_group_name} on a re-import ({@code
 * ImportCommitService#upsertParticipantProfile}): the pre-B5 behavior always preserved the existing
 * stored value when the imported cell was blank, regardless of whether the target was mapped at
 * all. That is wrong for "only the most recent group counts" - if the council maps a real 'Tidigare
 * grupp' column and this row's cell happens to be blank (the person had no prior group), the blank
 * must CLEAR any stale value from an earlier import. Leaving the target entirely unmapped (this
 * sheet has no opinion on previous group at all) must still preserve whatever is already stored.
 */
@SpringBootTest
class ImportCommitServicePreviousGroupTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private ImportCommitService importCommitService;
    @Autowired
    private ImportSessionService importSessionService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    /** Commits a one-row workbook (externalId, firstName, previousGroupName) for the same {@code
     *  externalId} across calls, so successive commits merge onto the same person/profile
     *  ({@code resolvePerson}'s externalId lookup, M3 review finding 1). Passing {@code
     *  mapPreviousGroup=false} omits the previousGroupName column mapping entirely (simulating a
     *  re-import sheet with no opinion on it), regardless of {@code previousGroupValue}. */
    private void commitOnce(String planId, String externalId, String previousGroupValue, boolean mapPreviousGroup) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Herr");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("MedlemsId");
            header.createCell(1).setCellValue("Förnamn");
            header.createCell(2).setCellValue("Tidigare grupp");
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue(externalId);
            dataRow.createCell(1).setCellValue("Test");
            if (previousGroupValue != null) {
                dataRow.createCell(2).setCellValue(previousGroupValue);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ImportSessionService.CreatedSession created =
                    importSessionService.createSession(planId, "tiny.xlsx", new ByteArrayInputStream(out.toByteArray()));
            ImportSession session = importSessionService.get(created.sessionId());
            session.setHeaderRow("Herr", 0);
            List<ColumnMapping> mappings = mapPreviousGroup
                    ? List.of(
                            new ColumnMapping(0, MappingTargetKind.EXTERNAL_ID, null),
                            new ColumnMapping(1, MappingTargetKind.FIRST_NAME, null),
                            new ColumnMapping(2, MappingTargetKind.PREVIOUS_GROUP_NAME, null))
                    : List.of(
                            new ColumnMapping(0, MappingTargetKind.EXTERNAL_ID, null),
                            new ColumnMapping(1, MappingTargetKind.FIRST_NAME, null));
            session.setMappings("Herr", mappings);

            importCommitService.commit(session, planId, CommitOptions.none());
        }
    }

    private String previousGroupNameOf(String planId, String externalId) {
        Person person = personRepository.findByExternalId(externalId).orElseThrow();
        ParticipantProfile profile =
                participantProfileRepository.findByPersonIdAndActivityPlanId(person.id(), planId).orElseThrow();
        return profile.previousGroupName();
    }

    @Test
    void mappedBlankCellClearsExistingPreviousGroupName() throws Exception {
        String planId = createPlan();
        String externalId = "ext-clear-" + Uuid7.generate();

        commitOnce(planId, externalId, "Torsdag Herr 2", true);
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Torsdag Herr 2");

        commitOnce(planId, externalId, null, true); // Mapped, but this row's cell is blank.
        assertThat(previousGroupNameOf(planId, externalId)).isNull();
    }

    @Test
    void unmappedTargetPreservesExistingPreviousGroupName() throws Exception {
        String planId = createPlan();
        String externalId = "ext-preserve-" + Uuid7.generate();

        commitOnce(planId, externalId, "Torsdag Herr 2", true);
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Torsdag Herr 2");

        commitOnce(planId, externalId, null, false); // previousGroupName not mapped at all this time.
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Torsdag Herr 2");
    }

    @Test
    void mappedNonBlankValueOverwritesExistingPreviousGroupName() throws Exception {
        String planId = createPlan();
        String externalId = "ext-overwrite-" + Uuid7.generate();

        commitOnce(planId, externalId, "Torsdag Herr 2", true);
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Torsdag Herr 2");

        commitOnce(planId, externalId, "Torsdag Herr 5", true);
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Torsdag Herr 5");
    }

    /**
     * FIX2 (BLOCKER, B5 review): the synthetic block-group column yields {@code null} for a Kölista
     * (waitlist) row by construction - a waitlisted player is not listed under any group block at all,
     * so there is nothing to derive a "previous group" from. That {@code null} is the chooser's own
     * "no opinion" on this row, not the file asserting "this person has no previous group" - so when
     * the ONLY previousGroupName mapping on the sheet is the synthetic column, a Kölista row's blank
     * must NOT clear an existing stored value (unlike a REAL mapped column's blank, which still does -
     * see {@link #mappedBlankCellClearsExistingPreviousGroupName}).
     *
     * <p>Re-imports this app's own {@code GroupedExportWorkbookBuilder} council-layout fixture (a real
     * grouped-export shape, 3 groups + a 2-entry Kölista) with only the synthetic column mapped to
     * previousGroupName; the Kölista entry "Jonas Karlsson" is forced (via an explicit {@code
     * RowDecision.matchExisting}) to resolve to the SAME person who already carries a previousGroupName
     * from an earlier import - the fixture itself carries no externalId column, so person identity is
     * pinned explicitly rather than relying on name matching, keeping this test about the blank-clears
     * semantics alone.
     */
    @Test
    void kolistaRowWithOnlySyntheticColumnMappedPreservesExistingPreviousGroupName() throws Exception {
        String planId = createPlan();
        String externalId = "ext-c-" + Uuid7.generate();

        commitOnce(planId, externalId, "Gammal Grupp", true);
        String personId = personRepository.findByExternalId(externalId).orElseThrow().id();
        assertThat(previousGroupNameOf(planId, externalId)).isEqualTo("Gammal Grupp");

        byte[] bytes = GroupedExportWorkbookBuilder.build();
        ImportSessionService.CreatedSession created =
                importSessionService.createSession(planId, "export.xlsx", new ByteArrayInputStream(bytes));
        ImportSession session = importSessionService.get(created.sessionId());
        String sheetName = created.sheets().get(0).name();
        session.setHeaderRow(sheetName, session.headerRowIndex(sheetName));
        assertThat(session.blockStructure(sheetName)).as("Layout 1 must be detected against this app's own export").isPresent();

        // Only the synthetic block column is mapped to previousGroupName - no real "Tidigare grupp"
        // column mapping at all.
        session.setMappings(sheetName, List.of(
                new ColumnMapping(0, MappingTargetKind.DISPLAY_NAME, null),
                new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, MappingTargetKind.PREVIOUS_GROUP_NAME, null)));

        ParsedSheet sheet = session.sheetOrThrow(sheetName);
        int kolistaRow = -1;
        for (int r = 0; r < sheet.rowCount(); r++) {
            if ("Jonas Karlsson".equals(sheet.cellAt(r, 0).rawString())) {
                kolistaRow = r;
            }
        }
        assertThat(kolistaRow).as("the Kölista entry 'Jonas Karlsson' must be found in the export").isGreaterThan(-1);
        session.setDecision(sheetName, kolistaRow, RowDecision.matchExisting(personId));

        importCommitService.commit(session, planId, CommitOptions.none());

        assertThat(previousGroupNameOf(planId, externalId))
                .as("a Kölista row's blank synthetic-column value must not clear an existing previousGroupName")
                .isEqualTo("Gammal Grupp");
    }
}
