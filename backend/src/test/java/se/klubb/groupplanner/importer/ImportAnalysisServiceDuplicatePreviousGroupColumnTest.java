package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * FIX3 (MAJOR, B5 review, both reviewers): the one-click {@code ImportAnalysisService} (and {@code
 * ImportController#columns}) used to track only the FIRST real column whose header text
 * independently suggested {@code previousGroupName} - a SECOND (or later) real column with its own
 * synonym header text (e.g. "Förra gruppen" alongside "Tidigare grupp" - both real-column synonyms
 * per {@code ColumnMappingSuggester}) kept its own, entirely independent {@code previousGroupName}
 * decision untouched, so the one-click auto-suggested mapping could carry TWO {@code
 * previousGroupName} targets at once - silently last-wins in {@code RowExtractor}. The fix collects
 * ALL such columns and downgrades every one but the eventual winner to {@code ignore}, and {@link
 * ImportSession#setMappings} now also refuses (as a central backstop) to ever store two.
 */
@SpringBootTest
class ImportAnalysisServiceDuplicatePreviousGroupColumnTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private ImportSessionService importSessionService;
    @Autowired
    private ImportAnalysisService importAnalysisService;
    @Autowired
    private ImportCommitService importCommitService;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    /**
     * Minimal Layout-1 (repeated-header, {@link BlockStructureDetector}) workbook, hand-built via POI:
     * two group blocks (the minimum {@code BlockStructureDetector} requires), each a single-cell
     * heading followed by a 3-column player-header row ("Namn"/"Tidigare grupp"/"Förra gruppen" - BOTH
     * of the latter two are real-column {@code previousGroupName} synonyms per {@code
     * ColumnMappingSuggester}) and one player row.
     */
    private byte[] buildTwoRealPreviousGroupColumnsWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Herr");
            int r = 0;
            sheet.createRow(r++).createCell(0).setCellValue("Grupp A");
            Row header1 = sheet.createRow(r++);
            header1.createCell(0).setCellValue("Namn");
            header1.createCell(1).setCellValue("Tidigare grupp");
            header1.createCell(2).setCellValue("Förra gruppen");
            Row alice = sheet.createRow(r++);
            alice.createCell(0).setCellValue("Alice Andersson");
            alice.createCell(1).setCellValue("Grupp 1 (Vårtermin 2024)");
            alice.createCell(2).setCellValue("Grupp 1 (Vårtermin 2024)");
            r++; // blank separator row between blocks.

            sheet.createRow(r++).createCell(0).setCellValue("Grupp B");
            Row header2 = sheet.createRow(r++);
            header2.createCell(0).setCellValue("Namn");
            header2.createCell(1).setCellValue("Tidigare grupp");
            header2.createCell(2).setCellValue("Förra gruppen");
            Row bob = sheet.createRow(r++);
            bob.createCell(0).setCellValue("Bob Bengtsson");
            bob.createCell(1).setCellValue("Grupp 2 (Vårtermin 2024)");
            bob.createCell(2).setCellValue("Grupp 2 (Vårtermin 2024)");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void twoRealHistoryColumnsPlusBlockStructureYieldsExactlyOneMappedCandidate() throws Exception {
        String planId = createPlan();
        byte[] bytes = buildTwoRealPreviousGroupColumnsWorkbook();

        ImportSessionService.CreatedSession created =
                importSessionService.createSession(planId, "dup.xlsx", new ByteArrayInputStream(bytes));
        ImportSession session = importSessionService.getForPlan(created.sessionId(), planId);

        // No manual header/mapping at all - exactly what the "one click" wizard path does.
        ImportAnalysis analysis = importAnalysisService.analyzeAndPrepare(session, planId);

        assertThat(session.blockStructure(session.selectedSheetOrThrow()))
                .as("Layout 1 must be detected against this hand-built two-block fixture").isPresent();

        long previousGroupNameColumns = analysis.columns().stream()
                .filter(c -> "previousGroupName".equals(c.target()))
                .count();
        assertThat(previousGroupNameColumns)
                .as("exactly one column may ever be auto-suggested for previousGroupName").isEqualTo(1);

        ImportAnalysis.ColumnAnalysis forraGruppen = analysis.columns().stream()
                .filter(c -> "Förra gruppen".equals(c.headerText()))
                .findFirst().orElseThrow();
        assertThat(forraGruppen.target())
                .as("the SECOND real previousGroupName-suggesting column must be downgraded to ignore")
                .isEqualTo("ignore");

        // One-click path must commit cleanly - the ImportSession-level uniqueness guard would throw a
        // BadRequestException on session.setMappings(...) (already called by analyzeAndPrepare above)
        // if the downgrade logic above had a bug and still produced two previousGroupName mappings.
        CommitResult commitResult = importCommitService.commit(session, planId, CommitOptions.none());
        assertThat(commitResult.imported()).isGreaterThan(0);
    }
}
