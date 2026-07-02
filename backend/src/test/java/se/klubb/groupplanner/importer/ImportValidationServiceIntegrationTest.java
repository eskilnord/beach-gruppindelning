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
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * saknat namn, dubbletter (within file), saknad ranking, ogiltiga tal, okänd tidigare grupp,
 * ogiltiga tider, tomma rader, potentiella dubbletter mot befintliga personer.
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
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private void seedTrainingGroup(String activityPlanId, String name) {
        jdbcClient.sql("INSERT INTO training_group (id, activity_plan_id, name) VALUES (:id, :planId, :name)")
                .param("id", Uuid7.generate())
                .param("planId", activityPlanId)
                .param("name", name)
                .update();
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

        // "okänd tidigare grupp" needs a known set to compare against: seed exactly p001's previous
        // group as the only known one, so p001's row is NOT flagged but every other group is.
        seedTrainingGroup(planId, "Torsdag Herr 1 (Hösttermin 2025)");

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

        // saknat namn: the group "N spelare" count row and the section heading rows.
        assertThat(resultFor(results, built.row("group1CountRow")).status()).isEqualTo(RowStatus.SKIP);
        assertThat(resultFor(results, built.row("group1CountRow")).reasons()).contains("Saknar namn");
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

        // okänd tidigare grupp: p001 matches the seeded known group; p003 (a different group) does not.
        assertThat(p001.reasons()).noneMatch(reason -> reason.startsWith("Okänd tidigare grupp"));
        assertThat(p003.reasons()).anyMatch(reason -> reason.startsWith("Okänd tidigare grupp"));

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

    @Test
    void okandTidigareGruppCheckIsSkippedWhenNoTrainingGroupsExistAnywhereYet() throws Exception {
        // Other tests in this class share the same temp-dir database (Spring context caching per
        // docs/plan.md test conventions) - clear the table explicitly so this test's "nothing to
        // compare against yet" premise holds regardless of method execution order.
        jdbcClient.sql("DELETE FROM training_group").update();

        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ImportSession session = uploadFixtureAndMap(built, planId);

        List<RowValidationResult> results = importValidationService.validate(session, planId);

        assertThat(results).noneMatch(r -> r.reasons().stream().anyMatch(reason -> reason.startsWith("Okänd tidigare grupp")));
    }
}
