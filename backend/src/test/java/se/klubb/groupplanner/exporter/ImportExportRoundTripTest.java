package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.importer.ColumnMapping;
import se.klubb.groupplanner.importer.CommitOptions;
import se.klubb.groupplanner.importer.CommitResult;
import se.klubb.groupplanner.importer.ImportCommitService;
import se.klubb.groupplanner.importer.ImportSession;
import se.klubb.groupplanner.importer.ImportSessionService;
import se.klubb.groupplanner.importer.ImportValidationService;
import se.klubb.groupplanner.importer.MappingTargetKind;
import se.klubb.groupplanner.importer.RowValidationResult;
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
import se.klubb.groupplanner.util.Uuid7;

/**
 * WP1 end-to-end proof: exporting a plan's groups to the "council layout" xlsx and re-importing that
 * exact file into a brand-new plan derives each player's {@code previousGroupName} from the group
 * block they were listed under - without a real "Tidigare grupp" column carrying any value at all
 * (the export's own such column is blank here, since none of {@code small-10}'s seeded participants
 * have a {@code previousGroupName} to begin with) - proving {@link
 * se.klubb.groupplanner.importer.BlockStructureDetector}'s Layout 1 detection and the synthetic
 * {@link ColumnMapping#BLOCK_GROUP_COLUMN_INDEX} column work end to end against a REAL export, not
 * just the hand-built {@code GroupedExportWorkbookBuilder} fixture.
 */
@SpringBootTest
class ImportExportRoundTripTest {

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
    private ImportSessionService importSessionService;
    @Autowired
    private ImportValidationService importValidationService;
    @Autowired
    private ImportCommitService importCommitService;

    @Test
    void previousGroupNameSurvivesAnExportThenImportRoundTrip() throws Exception {
        // --- Source plan: schedule small-10 into 2 groups, export the council-layout xlsx. ---
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String sourcePlanId = loader.load("small-10");
        ExportTestFixture.scheduleSomeLeaveOthersWaitlisted(
                sourcePlanId, trainingGroupRepository, trainingBlockRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachAssignmentRepository);

        List<TrainingGroup> sourceGroups = trainingGroupRepository.findByActivityPlanId(sourcePlanId);
        Set<String> sourceGroupNames = sourceGroups.stream().map(TrainingGroup::name).collect(Collectors.toSet());
        assertThat(sourceGroupNames).as("small-10 + the fixture's scheduling must produce >=2 groups").hasSizeGreaterThanOrEqualTo(2);

        ExportService.ExportFile exported = exportService.export(sourcePlanId, "xlsx", "grouped", false);

        // --- A brand-new, otherwise-empty plan (same DB - `training_group` rows from BOTH plans are
        // visible to knownGroupNames(), matching real life: last term's groups are a different plan). ---
        Instant now = Instant.now();
        SeasonPlan newSeason = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "WP1 round-trip", null, null, "active", now, now));
        ActivityPlan targetPlan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), newSeason.id(), "Round-trip", "beach", "draft", null, null, null, null, now, now));
        String targetPlanId = targetPlan.id();

        ImportSessionService.CreatedSession created = importSessionService.createSession(
                targetPlanId, "export.xlsx", new ByteArrayInputStream(exported.bytes()));
        ImportSession session = importSessionService.getForPlan(created.sessionId(), targetPlanId);
        String sheetName = created.sheets().get(0).name();

        // Adversarial review item 6: the session's own DEFAULT header row (not a hardcoded 0) must
        // already have skipped past the width-1 group-heading row and landed on the real repeated
        // "Namn/Ranking/..." header - proven here against a REAL export, not just the hand-built
        // GroupedExportWorkbookBuilder fixture.
        int defaultHeaderRow = session.headerRowIndex(sheetName);
        assertThat(defaultHeaderRow).as("default header must skip past the width-1 heading row").isGreaterThan(0);
        session.setHeaderRow(sheetName, defaultHeaderRow);

        assertThat(session.blockStructure(sheetName)).as("Layout 1 must be detected against a real export").isPresent();

        // Namn (col 0) -> displayName, Ranking (col 1) -> rankingPoints, the synthetic block-group
        // column -> previousGroupName. Deliberately NOT mapping the export's own (blank, since none of
        // small-10's participants had a previousGroupName to begin with) "Tidigare grupp" column.
        session.setMappings(sheetName, List.of(
                new ColumnMapping(0, MappingTargetKind.DISPLAY_NAME, null),
                new ColumnMapping(1, MappingTargetKind.RANKING_POINTS, null),
                new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, MappingTargetKind.PREVIOUS_GROUP_NAME, null)));

        List<RowValidationResult> validation = importValidationService.validate(session, targetPlanId);
        assertThat(validation).noneMatch(r -> r.reasons().stream().anyMatch(reason -> reason.startsWith("Okänd tidigare grupp")));

        CommitResult commitResult = importCommitService.commit(session, targetPlanId, CommitOptions.none());
        assertThat(commitResult.imported()).isGreaterThan(0);

        List<ParticipantProfile> importedParticipants = participantProfileRepository.findByActivityPlanId(targetPlanId);
        Set<String> importedPreviousGroupNames = importedParticipants.stream()
                .map(ParticipantProfile::previousGroupName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        assertThat(importedPreviousGroupNames).isNotEmpty();
        assertThat(sourceGroupNames).as("every derived previous group name must be one of the source plan's real group names")
                .containsAll(importedPreviousGroupNames);
    }
}
