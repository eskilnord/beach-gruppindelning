package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/** Flat layout (spec §20.2): one row per player + one per waitlisted participant, columns {@code
 * Grupp, Tid, Bana, Tränare, Spelare, Ranking, Nivåscore, Tidigare grupp[, Kommentar], Varningar}.
 * CSV must be {@code ;}-delimited UTF-8 with a leading BOM (M8 task item 4). */
@SpringBootTest
class FlatExportTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

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
    void flatCsvHasUtf8BomAndSemicolonDelimitedSpecColumns() throws Exception {
        String planId = loadAndSchedule();
        ExportService.ExportFile file = exportService.export(planId, "csv", "flat", false);

        assertThat(file.filename()).endsWith(".csv");
        assertThat(file.contentType()).isEqualTo(ExportService.ContentTypes.CSV);

        byte[] bytes = file.bytes();
        assertThat(bytes).startsWith(UTF8_BOM[0], UTF8_BOM[1], UTF8_BOM[2]);

        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = text.split("\\r\\n|\\n");
        String[] header = lines[0].split(";");
        assertThat(header).containsExactly(
                "Grupp", "Tid", "Bana", "Tränare", "Spelare", "Ranking", "Nivåscore", "Tidigare grupp", "Varningar");

        // 8 placed + 2 waitlisted = 10 data rows, per ExportTestFixture's split.
        assertThat(lines.length - 1).isEqualTo(10);
        assertThat(text).contains("Kölista");
    }

    @Test
    void flatXlsxHasOneRowPerPlayerPlusWaitlist() throws Exception {
        String planId = loadAndSchedule();
        ExportService.ExportFile file = exportService.export(planId, "xlsx", "flat", false);
        assertThat(file.filename()).endsWith(".xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(0);
            List<String> headerValues = List.of(
                    text(header, 0, formatter), text(header, 1, formatter), text(header, 2, formatter),
                    text(header, 3, formatter), text(header, 4, formatter));
            assertThat(headerValues).containsExactly("Grupp", "Tid", "Bana", "Tränare", "Spelare");

            // header + 10 data rows.
            assertThat(sheet.getLastRowNum()).isEqualTo(10);
            long kolistaRows = 0;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                if ("Kölista".equals(text(sheet.getRow(r), 0, formatter))) {
                    kolistaRows++;
                }
            }
            assertThat(kolistaRows).isEqualTo(2);
        }
    }

    @Test
    void includeCommentsAddsAKommentarColumnToFlatCsv() throws Exception {
        String planId = loadAndSchedule();
        ExportService.ExportFile file = exportService.export(planId, "csv", "flat", true);
        String text = new String(file.bytes(), 3, file.bytes().length - 3, StandardCharsets.UTF_8);
        String headerLine = text.split("\\r\\n|\\n")[0];
        assertThat(headerLine.split(";")).contains("Kommentar");
    }

    @Test
    void groupedLayoutRejectsCsvFormat() {
        String planId = loadAndSchedule();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exportService.export(planId, "csv", "grouped", false))
                .isInstanceOf(se.klubb.groupplanner.api.error.BadRequestException.class);
    }

    private static String text(Row row, int col, DataFormatter formatter) {
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }
}
