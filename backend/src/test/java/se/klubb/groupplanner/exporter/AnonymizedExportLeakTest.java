package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
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
 * Anonymized-export leak test (spec §21.3, M8 task item 4): no real name/email/phone string from the
 * DB appears anywhere in the output bytes (xlsx entries decompressed via {@link
 * CommentLeakExportTest#allDecompressedText}, csv decoded) — while the structure the
 * open-source-debugging dataset is FOR (ranking, level, wish shape over anonymized ids, group
 * names, time labels) demonstrably survives.
 */
@SpringBootTest
class AnonymizedExportLeakTest {

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
    private AnonymizedExportService anonymizedExportService;

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
        // M8 review fix (finding 4a/4c): plant phone, a personnummer-LIKE external id (constructed
        // at RUNTIME - a committed "DDMMYY-NNNN" literal would trip the repo's confidentiality
        // scanner regex on this source file) and person notes on EVERY person in the plan, so the
        // identifyingStrings sweep below asserts against real planted data, not just fixture emails.
        List<String> planPersonIds = new java.util.ArrayList<>();
        participantProfileRepository.findByActivityPlanId(planId).forEach(p -> planPersonIds.add(p.personId()));
        coachProfileRepository.findByActivityPlanId(planId).forEach(c -> planPersonIds.add(c.personId()));
        for (String personId : planPersonIds) {
            int n = PERSON_SEQ.getAndIncrement();
            Person person = personRepository.findById(personId).orElseThrow();
            personRepository.update(new Person(
                    person.id(), person.firstName(), person.lastName(), person.displayName(), person.email(),
                    "070-444-" + String.format("%04d", n), "000201" + "-" + String.format("%04d", n),
                    person.canBeParticipant(), person.canBeCoach(), "KANSLIG-M8-ANON-ANTECKNING-" + n,
                    person.createdAt(), person.updatedAt()));
        }
        return planId;
    }

    /** Class-global: {@code person.external_id} is UNIQUE and all test methods share one DB. */
    private static final java.util.concurrent.atomic.AtomicInteger PERSON_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void noRealNameOrEmailFromTheDbAppearsInTheOutputBytes() throws Exception {
        String planId = loadAndSchedule();

        String xlsxText = CommentLeakExportTest.allDecompressedText(anonymizedExportService.export(planId, "xlsx"));
        String csvText = new String(anonymizedExportService.export(planId, "csv"), StandardCharsets.UTF_8);

        // Gather every real identifying string actually in the DB for this plan's people (players
        // AND coaches - a coach name in a group block is as identifying as a player name).
        List<String> planPersonIds = new java.util.ArrayList<>();
        participantProfileRepository.findByActivityPlanId(planId).forEach(p -> planPersonIds.add(p.personId()));
        coachProfileRepository.findByActivityPlanId(planId).forEach(c -> planPersonIds.add(c.personId()));

        for (String personId : planPersonIds) {
            Person person = personRepository.findById(personId).orElseThrow();
            for (String sensitive : identifyingStrings(person)) {
                assertThat(xlsxText).as("xlsx must not contain '%s'", sensitive).doesNotContain(sensitive);
                assertThat(csvText).as("csv must not contain '%s'", sensitive).doesNotContain(sensitive);
            }
        }
    }

    @Test
    void structureSurvives_rankingLevelWishShapeGroupSizesAndTimes() throws Exception {
        String planId = loadAndSchedule();
        String csvText = new String(anonymizedExportService.export(planId, "csv"), StandardCharsets.UTF_8);

        // Anonymized ids present.
        assertThat(csvText).contains("Spelare 001");
        assertThat(csvText).contains("Tränare 01");

        // Ranking survives: small-10's p001 has ranking 325 (fixture-committed value).
        ParticipantProfile first = participantProfileRepository.findByActivityPlanId(planId).get(0);
        assertThat(first.rankingPoints()).isNotNull();
        assertThat(csvText).contains(String.valueOf(first.rankingPoints()));

        // Times survive (small-10's committed slot labels).
        assertThat(csvText).contains("Torsdag 18.00–19.30");

        // Wish structure survives BY ANONYMIZED ID: small-10's p003<->p004 mutual playWith pair
        // means at least one row's "Vill spela med" column references another Spelare NNN id.
        String[] lines = csvText.split("\\r\\n|\\n");
        String[] header = lines[0].split(";");
        int wishCol = java.util.Arrays.asList(header).indexOf("Vill spela med");
        assertThat(wishCol).isGreaterThanOrEqualTo(0);
        boolean anyWishReference = false;
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(";", -1);
            if (cols.length > wishCol && cols[wishCol].contains("Spelare ")) {
                anyWishReference = true;
                break;
            }
        }
        assertThat(anyWishReference)
                .as("at least one wish must survive as a reference to an anonymized 'Spelare NNN' id")
                .isTrue();

        // Group sizes survive implicitly: 8 placed rows across the 2 named groups + 2 Kölista rows.
        long kolistaRows = java.util.Arrays.stream(lines).skip(1).filter(l -> l.startsWith("Kölista")).count();
        assertThat(kolistaRows).isEqualTo(2);
        assertThat(lines.length - 1).isEqualTo(10);
    }

    private static List<String> identifyingStrings(Person person) {
        List<String> out = new java.util.ArrayList<>();
        addIfMeaningful(out, person.firstName());
        addIfMeaningful(out, person.lastName());
        addIfMeaningful(out, person.displayName());
        addIfMeaningful(out, person.email());
        addIfMeaningful(out, person.phone());
        // M8 review fix (finding 4c): the personnummer-like external id and the person's free-text
        // notes are exactly the "personliga identifierare" spec §21.3 says the anonymized export
        // removes - planted at runtime by loadAndSchedule(), so these are never vacuously null.
        addIfMeaningful(out, person.externalId());
        addIfMeaningful(out, person.notes());
        if (person.firstName() != null && person.lastName() != null) {
            out.add(person.firstName() + " " + person.lastName());
        }
        return out;
    }

    /** M8 review fix (finding 6): an unknown {@code format} is a 400 ({@code BadRequestException}
     * through the shared handler), matching the normal export endpoint's validation — previously it
     * silently fell through to xlsx. Blank/null still defaults to xlsx (the controller's own
     * {@code defaultValue}). */
    @Test
    void unknownFormatIsRejectedInsteadOfSilentlyDefaultingToXlsx() {
        String planId = loadAndSchedule();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> anonymizedExportService.export(planId, "pdf"))
                .isInstanceOf(se.klubb.groupplanner.api.error.BadRequestException.class)
                .hasMessageContaining("format");
        // Defaulting behavior preserved for absent/blank.
        assertThat(anonymizedExportService.export(planId, null)).isNotEmpty();
        assertThat(anonymizedExportService.export(planId, "")).isNotEmpty();
    }

    /** Skips blank/too-short values: a 1-2 char string (or null) can collide with legitimate
     * content (e.g. a column header letter) without being identifying. small-10's fixture names are
     * all full-length, so nothing real is skipped in practice. */
    private static void addIfMeaningful(List<String> out, String value) {
        if (value != null && value.strip().length() >= 3) {
            out.add(value.strip());
        }
    }
}
