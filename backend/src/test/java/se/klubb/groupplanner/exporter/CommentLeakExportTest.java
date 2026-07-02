package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.savedplan.SavedPlanService;
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
 * THE definitive §23.9 comment-leak test (spec §20.3/§21.2, CLAUDE.md, M8 task item 2): with
 * comments present in the DB and {@code includeComments=false}, the comment text must not appear
 * ANYWHERE in the produced file's bytes — for xlsx that means scanning every DECOMPRESSED zip entry
 * (an xlsx is a zip; string content lives deflated inside {@code xl/sharedStrings.xml}, so scanning
 * the raw compressed bytes would prove nothing), for csv the decoded text.
 *
 * <p>Includes a POSITIVE control ({@code includeComments=true} → the comment IS found by the exact
 * same scanner) so the negative assertions can never pass vacuously because of a broken scanner.
 * Also covers the ANONYMIZED export, where comments must be absent even though no {@code
 * includeComments} parameter exists at all (spec §21.3 strips comments unconditionally).
 *
 * <p><b>M8 review fix (finding 4) hardening:</b> the planted sensitive surface now also covers
 * {@code person.phone}, {@code person.external_id} (a personnummer-LIKE value constructed AT
 * RUNTIME by string concatenation — never a committed literal, so the repo's confidentiality
 * scanner regex can't fire on this source file), {@code person.notes}, the fixture's own emails,
 * and a sensitive custom TEXT field ("Medicinsk info") created through the REAL field API — all
 * asserted absent from every normal export variant (both {@code includeComments} values: the
 * comment opt-in must never widen to contact/identity/medical data) and, for the custom field,
 * from {@code saved_plan.snapshot_json} too.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentLeakExportTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String SENSITIVE_COMMENT = "KANSLIG-M8-EXPORT-KOMMENTAR-qvx318";
    private static final String SENSITIVE_NOTE = "KANSLIG-M8-EXPORT-INTERNANTECKNING-wzy742";
    private static final String SENSITIVE_MEDICAL = "Medicinsk info: KANSLIG-M8-MEDICINSK-UPPGIFT-pqr654";
    private static final String SENSITIVE_PERSON_NOTE = "KANSLIG-M8-PERSONANTECKNING-stu987";

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
    @Autowired
    private AnonymizedExportService anonymizedExportService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SavedPlanService savedPlanService;
    @Autowired
    private JdbcClient jdbcClient;

    private String loadScheduleAndAddCommentsToEveryone() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("small-10");
        ExportTestFixture.scheduleSomeLeaveOthersWaitlisted(
                planId, trainingGroupRepository, trainingBlockRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachAssignmentRepository);
        // EVERY participant gets the sensitive comment/note - placed AND waitlisted, so a leak
        // through either the group-player path or the Kölista path would be caught.
        for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(planId)) {
            participantProfileRepository.update(new ParticipantProfile(
                    p.id(), p.personId(), p.activityPlanId(), p.rankingPoints(), p.rankingSource(),
                    p.previousGroupName(), p.previousGroupLevel(), p.estimatedLevel(), p.levelConfidence(),
                    p.manualLevelScore(), SENSITIVE_COMMENT, SENSITIVE_NOTE, p.manualReviewFlag(), p.waitlisted()));
            // Finding 4a: every PERSON gets phone + a personnummer-LIKE external id + notes. The
            // external id is built at RUNTIME from concatenated pieces - a committed
            // "DDMMYY-NNNN" literal would trip scripts/check-no-confidential.sh's personnummer
            // regex on this very source file. Class-global sequence: external_id is UNIQUE and all
            // test methods of this class share one DB/Spring context.
            int n = PERSON_SEQ.getAndIncrement();
            Person person = personRepository.findById(p.personId()).orElseThrow();
            personRepository.update(new Person(
                    person.id(), person.firstName(), person.lastName(), person.displayName(), person.email(),
                    "070-555-" + String.format("%04d", n), fakePersonnummer(n), person.canBeParticipant(),
                    person.canBeCoach(), SENSITIVE_PERSON_NOTE + "-" + n, person.createdAt(), person.updatedAt()));
        }
        return planId;
    }

    private static final java.util.concurrent.atomic.AtomicInteger PERSON_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** Obviously-fake personnummer-shaped value ("000101-0NNN"), concatenated at runtime so the
     * committed source never contains a string the confidentiality scanner's regex matches. */
    private static String fakePersonnummer(int n) {
        return "000101" + "-" + String.format("%04d", n);
    }

    /** Creates the sensitive custom TEXT field through the REAL field API (finding 4d) and sets a
     * medical-info value on the FIRST participant via the real field-values endpoint. */
    private String plantSensitiveCustomField(String planId) throws Exception {
        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"medicinskInfo\",\"label\":\"Medicinsk info\",\"fieldType\":\"text\"}"))
                .andExpect(status().isCreated());
        String participantId = participantProfileRepository.findByActivityPlanId(planId).get(0).id();
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + participantId + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicinskInfo\":\"" + SENSITIVE_MEDICAL + "\"}"))
                .andExpect(status().isOk());
        return participantId;
    }

    @Test
    void commentsAreAbsentFromEveryDefaultExportVariantAtByteLevel() throws Exception {
        String planId = loadScheduleAndAddCommentsToEveryone();

        String groupedXlsxText = allDecompressedText(exportService.export(planId, "xlsx", "grouped", false).bytes());
        assertThat(groupedXlsxText).doesNotContain(SENSITIVE_COMMENT);
        assertThat(groupedXlsxText).doesNotContain(SENSITIVE_NOTE);

        String flatXlsxText = allDecompressedText(exportService.export(planId, "xlsx", "flat", false).bytes());
        assertThat(flatXlsxText).doesNotContain(SENSITIVE_COMMENT);
        assertThat(flatXlsxText).doesNotContain(SENSITIVE_NOTE);

        byte[] csvBytes = exportService.export(planId, "csv", "flat", false).bytes();
        String csvText = new String(csvBytes, StandardCharsets.UTF_8);
        assertThat(csvText).doesNotContain(SENSITIVE_COMMENT);
        assertThat(csvText).doesNotContain(SENSITIVE_NOTE);
    }

    /** Positive control: with the EXPLICIT opt-in, the imported comment IS found by the exact same
     * scanner - proving the negative assertions above cannot pass because the scanner is blind.
     * The INTERNAL note stays absent even when opted in: spec §20.3's opt-in covers the imported
     * registration comment ("Inkludera kommentarer i export"); {@code internalNote} is the
     * council's own private field and no export variant emits it at all. */
    @Test
    void includeCommentsTrueActuallyEmitsTheImportedCommentButNeverTheInternalNote() throws Exception {
        String planId = loadScheduleAndAddCommentsToEveryone();

        String groupedOptIn = allDecompressedText(exportService.export(planId, "xlsx", "grouped", true).bytes());
        assertThat(groupedOptIn).contains(SENSITIVE_COMMENT);
        assertThat(groupedOptIn).doesNotContain(SENSITIVE_NOTE);

        String csvOptIn = new String(exportService.export(planId, "csv", "flat", true).bytes(), StandardCharsets.UTF_8);
        assertThat(csvOptIn).contains(SENSITIVE_COMMENT);
        assertThat(csvOptIn).doesNotContain(SENSITIVE_NOTE);
    }

    @Test
    void anonymizedExportNeverContainsCommentsRegardlessOfAnyParameter() throws Exception {
        String planId = loadScheduleAndAddCommentsToEveryone();

        String xlsxText = allDecompressedText(anonymizedExportService.export(planId, "xlsx"));
        assertThat(xlsxText).doesNotContain(SENSITIVE_COMMENT);
        assertThat(xlsxText).doesNotContain(SENSITIVE_NOTE);

        String csvText = new String(anonymizedExportService.export(planId, "csv"), StandardCharsets.UTF_8);
        assertThat(csvText).doesNotContain(SENSITIVE_COMMENT);
        assertThat(csvText).doesNotContain(SENSITIVE_NOTE);
    }

    /** M8 review fix (finding 4b): email/phone/personnummer-like external id/person notes are NOT
     * export content in ANY normal variant — including {@code includeComments=true}, whose opt-in
     * covers exactly the imported registration comment and nothing else. Names are legitimately
     * present in the normal export (its whole purpose), so they are NOT asserted absent here — that
     * is the anonymized export's contract ({@code AnonymizedExportLeakTest}). */
    @Test
    void contactAndIdentityDataIsAbsentFromEveryNormalExportVariant() throws Exception {
        String planId = loadScheduleAndAddCommentsToEveryone();

        List<String> sensitive = new ArrayList<>();
        for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(planId)) {
            Person person = personRepository.findById(p.personId()).orElseThrow();
            sensitive.add(person.email());
            sensitive.add(person.phone());
            sensitive.add(person.externalId());
            sensitive.add(person.notes());
        }
        assertThat(sensitive).allSatisfy(s -> assertThat(s).isNotBlank()); // planting actually happened.

        List<String> outputs = List.of(
                allDecompressedText(exportService.export(planId, "xlsx", "grouped", false).bytes()),
                allDecompressedText(exportService.export(planId, "xlsx", "grouped", true).bytes()),
                allDecompressedText(exportService.export(planId, "xlsx", "flat", false).bytes()),
                allDecompressedText(exportService.export(planId, "xlsx", "flat", true).bytes()),
                new String(exportService.export(planId, "csv", "flat", false).bytes(), StandardCharsets.UTF_8),
                new String(exportService.export(planId, "csv", "flat", true).bytes(), StandardCharsets.UTF_8));
        for (String output : outputs) {
            for (String s : sensitive) {
                assertThat(output).doesNotContain(s);
            }
        }
    }

    /** M8 review fix (finding 4d): a sensitive custom TEXT field ("Medicinsk info"), created through
     * the REAL field API and valued through the real field-values endpoint, must appear in NO export
     * variant and NOT in {@code saved_plan.snapshot_json} — locking in that both pipelines only ever
     * consume the specific structured fields they were built for (priority/wishes/times), never
     * arbitrary custom-field text. */
    @Test
    void sensitiveCustomTextFieldNeverReachesExportsOrSavedPlanSnapshot() throws Exception {
        String planId = loadScheduleAndAddCommentsToEveryone();
        plantSensitiveCustomField(planId);

        List<String> outputs = List.of(
                allDecompressedText(exportService.export(planId, "xlsx", "grouped", true).bytes()),
                new String(exportService.export(planId, "csv", "flat", true).bytes(), StandardCharsets.UTF_8),
                allDecompressedText(anonymizedExportService.export(planId, "xlsx")),
                new String(anonymizedExportService.export(planId, "csv"), StandardCharsets.UTF_8));
        for (String output : outputs) {
            assertThat(output).doesNotContain(SENSITIVE_MEDICAL);
        }

        SavedPlan saved = savedPlanService.materialize(planId, "Snapshot med medicinskt fält", SavedPlan.STATUS_SAVED);
        String rawSnapshotJson = jdbcClient.sql("SELECT snapshot_json FROM saved_plan WHERE id = :id")
                .param("id", saved.id())
                .query(String.class)
                .single();
        assertThat(rawSnapshotJson).doesNotContain(SENSITIVE_MEDICAL);
        assertThat(rawSnapshotJson).doesNotContain("KANSLIG-M8-MEDICINSK");
    }

    /** Concatenates every zip entry's DECOMPRESSED bytes decoded as UTF-8 (entry name prepended) —
     * an xlsx is a zip whose cell strings live in {@code xl/sharedStrings.xml}/{@code
     * xl/worksheets/*.xml}; deflate would hide any string from a raw-byte scan, so decompression is
     * what makes this the "definitive" leak check the task calls for. */
    static String allDecompressedText(byte[] xlsxBytes) throws Exception {
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                all.append('\n').append(entry.getName()).append('\n');
                all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(all.length()).as("xlsx zip must have decompressable entries").isGreaterThan(0);
        return all.toString();
    }
}
