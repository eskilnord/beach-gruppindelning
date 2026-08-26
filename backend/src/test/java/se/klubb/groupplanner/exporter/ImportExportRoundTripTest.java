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
import se.klubb.groupplanner.importer.ImportAnalysis;
import se.klubb.groupplanner.importer.ImportAnalysisService;
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
    private ImportAnalysisService importAnalysisService;
    @Autowired
    private ImportCommitService importCommitService;

    /** Stale value seeded onto every participant before export (B5 FIX1 regression) - deliberately
     *  distinct from every real small-10 group name (which are always plain "&lt;category&gt; N", no
     *  term at all - {@code GroupGenerator}), so a committed previousGroupName ever equal to this
     *  proves the OLD (stale, real-column) value won instead of the current block heading. Also
     *  deliberately carries a term suffix ("Hösttermin 2020") - this is the EXACT shape of the FIX1
     *  blocker: a term-bearing real column vs a term-less block heading, where the pre-fix chooser
     *  always (and wrongly) favored the term-bearing side. */
    private static final String STALE_PREVIOUS_GROUP_NAME = "Gammal Grupp (Hösttermin 2020)";

    /** Schedules small-10 into >=2 groups and exports the council-layout xlsx (shared by both tests
     *  in this class - the round-trip proof below, and the B5 one-click-precedence proof). */
    private ExportRoundTripFixture setUpSourcePlanAndExport() throws Exception {
        return setUpSourcePlanAndExport(false);
    }

    /**
     * @param seedStalePreviousGroupNames when {@code true}, every small-10 participant's {@code
     *     previousGroupName} is set to {@link #STALE_PREVIOUS_GROUP_NAME} BEFORE exporting - so the
     *     export's own real "Tidigare grupp" column carries that stale value (this app's exporter
     *     always writes the participant's CURRENT stored previousGroupName into it), while the export's
     *     block structure carries each player's actual CURRENT group. B5 FIX1 regression: the one-click
     *     analysis must prefer the block heading (the newer information) over this stale real-column
     *     value, not the other way around - the pre-fix {@code PreviousGroupColumnChooser} term-
     *     recency rule got this backwards specifically because a real column routinely carries a term
     *     suffix while a block heading never does (see {@code PreviousGroupColumnChooserTest}).
     */
    private ExportRoundTripFixture setUpSourcePlanAndExport(boolean seedStalePreviousGroupNames) throws Exception {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String sourcePlanId = loader.load("small-10");
        ExportTestFixture.scheduleSomeLeaveOthersWaitlisted(
                sourcePlanId, trainingGroupRepository, trainingBlockRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachAssignmentRepository);

        if (seedStalePreviousGroupNames) {
            for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(sourcePlanId)) {
                participantProfileRepository.update(new ParticipantProfile(
                        p.id(), p.personId(), p.activityPlanId(), p.rankingPoints(), p.rankingSource(),
                        STALE_PREVIOUS_GROUP_NAME, p.previousGroupLevel(), p.estimatedLevel(), p.levelConfidence(),
                        p.manualLevelScore(), p.importedComment(), p.internalNote(), p.manualReviewFlag(),
                        p.waitlisted(), p.reviewedDone()));
            }
        }

        List<TrainingGroup> sourceGroups = trainingGroupRepository.findByActivityPlanId(sourcePlanId);
        Set<String> sourceGroupNames = sourceGroups.stream().map(TrainingGroup::name).collect(Collectors.toSet());
        assertThat(sourceGroupNames).as("small-10 + the fixture's scheduling must produce >=2 groups").hasSizeGreaterThanOrEqualTo(2);

        ExportService.ExportFile exported = exportService.export(sourcePlanId, "xlsx", "grouped", false);
        return new ExportRoundTripFixture(exported, sourceGroupNames);
    }

    private record ExportRoundTripFixture(ExportService.ExportFile exported, Set<String> sourceGroupNames) {
    }

    private String createTargetPlan() {
        Instant now = Instant.now();
        SeasonPlan newSeason = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "WP1 round-trip", null, null, "active", now, now));
        ActivityPlan targetPlan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), newSeason.id(), "Round-trip", "beach", "draft", null, null, null, null, now, now));
        return targetPlan.id();
    }

    @Test
    void previousGroupNameSurvivesAnExportThenImportRoundTrip() throws Exception {
        ExportRoundTripFixture fixture = setUpSourcePlanAndExport();
        ExportService.ExportFile exported = fixture.exported();
        Set<String> sourceGroupNames = fixture.sourceGroupNames();
        String targetPlanId = createTargetPlan();

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
        assertThat(validation).noneMatch(r -> r.reasons().stream().anyMatch(reason -> reason.startsWith("Tidigare grupp")));

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

    /**
     * B5 IMPORTANT round-trip check: the export's own 'Tidigare grupp' column is blank for every
     * small-10 participant (none of them had a previousGroupName to begin with), while the export's
     * own block structure (the group each player is CURRENTLY listed under) obviously carries a real
     * value for every row. Before B5, {@code ImportAnalysisService}'s one-click analysis always
     * preferred a real 'Tidigare grupp' column whenever ANY such column existed at all - regardless
     * of whether it actually had data - so re-uploading the app's own grouped export never
     * auto-suggested the (far more informative) synthetic block column without a manual override
     * (see the test above, which manually maps it). With the new precedence
     * ({@code PreviousGroupColumnChooser}), the empty real column loses to the synthetic block
     * column automatically (tie on term-recency, default-to-synthetic since the real column has zero
     * parseable samples) - this proves the ONE-CLICK path picks it with no manual mapping at all.
     *
     * <p>B5 FIX1 regression (review): the ORIGINAL version of this test was vacuous for the actual
     * blocker it was meant to guard against - small-10's participants start with no previousGroupName
     * at all, so the real column's win/loss made no visible difference either way. This now seeds a
     * STALE previousGroupName onto every participant before export (so the real column is non-empty
     * and carries genuinely wrong/outdated information), and asserts the current block heading still
     * wins and OVERWRITES that stale value on commit.
     */
    @Test
    void oneClickAnalysisOverOwnGroupedExportAutoPicksTheSyntheticBlockColumn() throws Exception {
        ExportRoundTripFixture fixture = setUpSourcePlanAndExport(true);
        Set<String> sourceGroupNames = fixture.sourceGroupNames();
        String targetPlanId = createTargetPlan();

        ImportSessionService.CreatedSession created = importSessionService.createSession(
                targetPlanId, "export.xlsx", new ByteArrayInputStream(fixture.exported().bytes()));
        ImportSession session = importSessionService.getForPlan(created.sessionId(), targetPlanId);

        // No manual header/mapping at all - exactly what the "one click" wizard path does.
        ImportAnalysis analysis = importAnalysisService.analyzeAndPrepare(session, targetPlanId);

        ImportAnalysis.ColumnAnalysis syntheticColumn = analysis.columns().stream()
                .filter(ImportAnalysis.ColumnAnalysis::synthetic)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Layout 1 must be detected against a real export"));
        assertThat(syntheticColumn.target()).isEqualTo("previousGroupName");
        assertThat(syntheticColumn.confidence()).isEqualTo(1.0);

        ImportAnalysis.ColumnAnalysis realPreviousGroupColumn = analysis.columns().stream()
                .filter(c -> "Tidigare grupp".equals(c.headerText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The export must still have its own 'Tidigare grupp' column"));
        assertThat(realPreviousGroupColumn.target())
                .as("B5 FIX1: the STALE real-column value must lose even though it is non-empty")
                .isEqualTo("ignore");

        // analyzeAndPrepare already wrote the mappings into the session - commit directly.
        CommitResult commitResult = importCommitService.commit(session, targetPlanId, CommitOptions.none());
        assertThat(commitResult.imported()).isGreaterThan(0);

        List<ParticipantProfile> importedParticipants = participantProfileRepository.findByActivityPlanId(targetPlanId);
        Set<String> importedPreviousGroupNames = importedParticipants.stream()
                .map(ParticipantProfile::previousGroupName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // Every committed participant's previousGroupName is their CURRENT group heading from the
        // export's own block structure - never the STALE real-column value seeded before export.
        assertThat(importedPreviousGroupNames).isNotEmpty();
        assertThat(importedPreviousGroupNames)
                .as("B5 FIX1: the current block heading must OVERWRITE the stale real-column value")
                .doesNotContain(STALE_PREVIOUS_GROUP_NAME);
        assertThat(sourceGroupNames).containsAll(importedPreviousGroupNames);
    }
}
