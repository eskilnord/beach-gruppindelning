package se.klubb.groupplanner.savedplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * M8 task item 1's leak test: "extend the leak test: snapshot a plan whose participants have
 * comments, assert absent" — the CLAUDE.md confidentiality rule applied to {@code
 * saved_plan.snapshot_json}, alongside the pre-existing {@code optimization_run}/{@code
 * explanation_record} leak tests ({@code OptimizationRunSnapshotLeakTest}, {@code
 * ExplanationRecordLeakTest}). Sets a deliberately distinctive {@code importedComment}/{@code
 * internalNote} on a participant, saves the plan via {@link SavedPlanService}, and asserts — via a
 * raw {@link JdbcClient} query against the actual DB column — that neither string appears anywhere
 * in {@code snapshot_json}.
 */
@SpringBootTest
class SavedPlanSnapshotLeakTest {

    private static final String SENSITIVE_IMPORTED_COMMENT = "KANSLIG-M8-ANMALNINGSKOMMENTAR-xyz789";
    private static final String SENSITIVE_INTERNAL_NOTE = "KANSLIG-M8-INTERN-ANTECKNING-abc123";

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
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private SavedPlanService savedPlanService;
    @Autowired
    private SavedPlanRepository savedPlanRepository;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void snapshotJsonNeverContainsImportedCommentOrInternalNoteText() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("small-10");

        ParticipantProfile someParticipant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        ParticipantProfile withComments = new ParticipantProfile(
                someParticipant.id(), someParticipant.personId(), someParticipant.activityPlanId(),
                someParticipant.rankingPoints(), someParticipant.rankingSource(), someParticipant.previousGroupName(),
                someParticipant.previousGroupLevel(), someParticipant.estimatedLevel(), someParticipant.levelConfidence(),
                someParticipant.manualLevelScore(), SENSITIVE_IMPORTED_COMMENT, SENSITIVE_INTERNAL_NOTE,
                someParticipant.manualReviewFlag(), someParticipant.waitlisted());
        participantProfileRepository.update(withComments);

        savedPlanService.materialize(planId, "Torsdag Nybörjare", SavedPlan.STATUS_SAVED);

        String rawSnapshotJson = jdbcClient.sql("SELECT snapshot_json FROM saved_plan WHERE activity_plan_id = :planId")
                .param("planId", planId)
                .query(String.class)
                .single();

        assertThat(rawSnapshotJson).isNotNull();
        assertThat(rawSnapshotJson).doesNotContain(SENSITIVE_IMPORTED_COMMENT);
        assertThat(rawSnapshotJson).doesNotContain(SENSITIVE_INTERNAL_NOTE);

        // Same assertion via the parsed/served view (SavedPlanDetailView.snapshot()), since that is
        // what actually reaches the frontend through GET .../saved-plans/{id}.
        SavedPlan saved = savedPlanRepository.findByActivityPlanId(planId).get(0);
        SavedPlanDetailView detail = savedPlanService.findOne(planId, saved.id());
        String servedJson = detail.snapshot().toString();
        assertThat(servedJson).doesNotContain(SENSITIVE_IMPORTED_COMMENT);
        assertThat(servedJson).doesNotContain(SENSITIVE_INTERNAL_NOTE);
    }
}
