package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * The grouped xlsx "council layout" golden-structure test (spec §20, docs/plan.md Context, M8 task
 * item 4): generates a real export, reparses the bytes with POI, and asserts the structural shape -
 * stacked column-A group metadata, a player header + rows per group, a player-count row, a blank
 * separator, and a trailing Kölista section - rather than asserting exact row numbers (which would
 * make the test brittle to any future cosmetic reordering).
 */
@SpringBootTest
class GroupedXlsxExportTest {

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
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private ExportService exportService;

    private String loadAndSchedule() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("small-10");
        ExportTestFixture.scheduleSomeLeaveOthersWaitlisted(
                planId, trainingGroupRepository, trainingBlockRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachAssignmentRepository);
        return planId;
    }

    @Test
    void groupedXlsxHasStackedMetadataPlayerRowsCountRowAndKolistaSection() throws Exception {
        String planId = loadAndSchedule();

        ExportService.ExportFile file = exportService.export(planId, "xlsx", "grouped", false);
        assertThat(file.filename()).endsWith(".xlsx");
        assertThat(file.contentType()).isEqualTo(ExportService.ContentTypes.XLSX);

        List<List<String>> rows = readAllRows(file.bytes());
        List<String> columnA = rows.stream().map(r -> r.isEmpty() ? "" : r.get(0)).toList();

        // 2 groups (small-10: target_size=5, 2 groups) - both group names appear in column A.
        List<String> groupNameRows = columnA.stream().filter(v -> v != null && !v.isBlank()
                        && !v.startsWith("Ordning") && !v.startsWith("Tid") && !v.startsWith("Tränare")
                        && !v.startsWith("Antal") && !v.equals("Namn") && !v.equals("Kölista"))
                .toList();
        assertThat(groupNameRows).hasSizeGreaterThanOrEqualTo(2);

        int firstGroupNameIdx = columnA.indexOf(groupNameRows.get(0));
        assertThat(columnA.get(firstGroupNameIdx + 1)).as("Ordning row directly follows the group name").startsWith("Ordning:");
        assertThat(columnA.get(firstGroupNameIdx + 2)).as("Tid row").startsWith("Tid:");
        assertThat(columnA.get(firstGroupNameIdx + 3)).as("Tränare row").startsWith("Tränare:");

        Row playerHeaderRow = null;
        for (Row row : sheetOf(file.bytes())) {
            if (row != null && cellText(row, 0).equals("Namn")) {
                playerHeaderRow = row;
                break;
            }
        }
        assertThat(playerHeaderRow).as("a 'Namn' player header row must exist").isNotNull();
        assertThat(cellText(playerHeaderRow, 1)).isEqualTo("Ranking");
        assertThat(cellText(playerHeaderRow, 2)).isEqualTo("Nivåscore");
        assertThat(cellText(playerHeaderRow, 3)).isEqualTo("Tidigare grupp");

        // "Antal spelare: 4" (4 players per group, from ExportTestFixture's 8-placed/2-waitlisted split).
        assertThat(columnA).anyMatch(v -> v != null && v.equals("Antal spelare: 4"));

        // A blank separator row exists between group blocks (index right after "Antal spelare: 4"
        // for the first group is either blank/absent or the next group's name - assert at least one
        // genuinely blank row exists overall, matching the writer's explicit separator).
        assertThat(rows).anyMatch(List::isEmpty);

        int kolistaIdx = columnA.indexOf("Kölista");
        assertThat(kolistaIdx).as("a trailing Kölista section must exist").isGreaterThan(firstGroupNameIdx);
        assertThat(columnA.get(kolistaIdx + 1)).isEqualTo("Namn");
        assertThat(columnA).anyMatch(v -> v != null && v.startsWith("Antal på kölista: "));
        // Exactly 2 waitlisted (10 participants, 8 placed by the fixture).
        assertThat(columnA).contains("Antal på kölista: 2");
    }

    @Test
    void defaultExportHasNoKommentarColumn() throws Exception {
        String planId = loadAndSchedule();
        ExportService.ExportFile file = exportService.export(planId, "xlsx", "grouped", false);
        for (Row row : sheetOf(file.bytes())) {
            if (row != null && cellText(row, 0).equals("Namn")) {
                assertThat(headerRowValues(row)).doesNotContain("Kommentar");
            }
        }
    }

    @Test
    void includeCommentsAddsAKommentarColumn() throws Exception {
        String planId = loadAndSchedule();
        ExportService.ExportFile file = exportService.export(planId, "xlsx", "grouped", true);
        boolean foundKommentarHeader = false;
        for (Row row : sheetOf(file.bytes())) {
            if (row != null && cellText(row, 0).equals("Namn") && headerRowValues(row).contains("Kommentar")) {
                foundKommentarHeader = true;
            }
        }
        assertThat(foundKommentarHeader).isTrue();
    }

    private static List<String> headerRowValues(Row row) {
        List<String> values = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            values.add(cellText(row, c));
        }
        return values;
    }

    private static Sheet sheetOf(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return workbook.getSheetAt(0);
        }
    }

    private static List<List<String>> readAllRows(byte[] bytes) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rows.add(List.of());
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private static String cellText(Row row, int col) {
        Cell cell = row.getCell(col);
        return cell == null ? "" : new DataFormatter().formatCellValue(cell);
    }
}
