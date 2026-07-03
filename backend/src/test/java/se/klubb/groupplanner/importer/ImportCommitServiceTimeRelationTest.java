package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * WI-A task 4: a {@code timeRelation}-mapped column (the fixture's "Tid" column, mapped to the
 * standard {@code cannotTimes} field) must never reach {@code writeCustomFieldValue} - post-M6a that
 * field stores a JSON array of real {@code time_slot} ids (spec {@code FieldValueService}), and a raw
 * imported cell like {@code "18:00"} or {@code "ej 21"} is a completely different, incompatible
 * shape. Before this fix it was written anyway (bypassing {@code FieldValueService} validation) and
 * silently ignored by the solver; now it must be skipped and reported as a row-level warning instead.
 */
@SpringBootTest
class ImportCommitServiceTimeRelationTest {

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
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    @Test
    void timeRelationMappedColumnProducesAWarningAndNoCustomFieldValueRow() throws Exception {
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();

        ImportSessionService.CreatedSession created =
                importSessionService.createSession(planId, "fixture.xlsx", new ByteArrayInputStream(built.bytes()));
        ImportSession session = importSessionService.get(created.sessionId());
        session.setHeaderRow(MessyWorkbookBuilder.SHEET_NAME, 0);
        session.setMappings(MessyWorkbookBuilder.SHEET_NAME, List.of(
                new ColumnMapping(1, MappingTargetKind.FIRST_NAME, null),
                new ColumnMapping(2, MappingTargetKind.LAST_NAME, null),
                new ColumnMapping(5, MappingTargetKind.EMAIL, null),
                // Column 6 ("Tid") mapped onto the standard cannotTimes field - a timeRelation type,
                // not free text, despite the raw cell holding things like "18:00" or "ej 21".
                new ColumnMapping(6, MappingTargetKind.CUSTOM_FIELD, "cannotTimes")));

        CommitResult result = importCommitService.commit(session, planId, CommitOptions.none());

        // Most fixture rows carry a "Tid" value (genuine Excel time cells, "ej 21", comma lists) -
        // they must all fold into ONE summary warning, not one warning per row. With more than ten
        // affected rows the summary takes the count form ("N rader: ...") instead of listing them.
        List<String> timeWarnings = result.warnings().stream()
                .filter(w -> w.contains(ImportCommitService.TIME_RELATION_IMPORT_WARNING))
                .toList();
        assertThat(timeWarnings).hasSize(1);
        assertThat(timeWarnings.getFirst()).matches("^\\d+ rader: .*");

        // The skipped time value must not skip the ROW: p001 itself is still imported.
        assertThat(result.imported()).isGreaterThan(0);

        FieldDefinition cannotTimes = fieldDefinitionRepository.findGlobalByKey("cannotTimes").orElseThrow();
        Person johan = personRepository.findAll().stream()
                .filter(p -> "johan.johansson1@example.se".equalsIgnoreCase(p.email()))
                .findFirst().orElseThrow();
        ParticipantProfile johanProfile =
                participantProfileRepository.findByPersonIdAndActivityPlanId(johan.id(), planId).orElseThrow();

        assertThat(customFieldValueRepository.findByEntity("participant", johanProfile.id()).stream()
                .anyMatch(v -> v.fieldDefinitionId().equals(cannotTimes.id())))
                .as("no custom_field_value row must exist for the timeRelation-mapped column")
                .isFalse();
    }
}
