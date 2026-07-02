package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * M7 extension of CLAUDE.md's comment-leak protection surface (task item 2's "add the leak assertion
 * anyway", and the general privacy rule): {@code explanation_record}'s five JSON columns and {@code
 * optimization_run.result_summary_json}'s new {@code constraintSummaries} array are new attack
 * surfaces in principle, even though every {@code ConstraintJustification} record structurally
 * carries only ids/names/counts/levels (never {@code importedComment}/{@code internalNote} text —
 * see {@code se.klubb.groupplanner.solver.constraints.Justifications}). Sets a deliberately
 * distinctive comment, runs a full explain flow (solve -&gt; explain player -&gt; explain plan), and
 * asserts — via raw {@link JdbcClient} queries against the actual DB columns — that neither sensitive
 * string appears anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class ExplanationRecordLeakTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String SENSITIVE_IMPORTED_COMMENT = "KANSLIG-M7-ANMALNINGSKOMMENTAR-xyz789";
    private static final String SENSITIVE_INTERNAL_NOTE = "KANSLIG-M7-INTERN-ANTECKNING-abc123";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
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
    private JdbcClient jdbcClient;

    @Test
    void explanationAndRunSummaryJsonNeverContainImportedCommentOrInternalNoteText() throws Exception {
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

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"profile\":\"FAST\"}"));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(jdbcClient.sql(
                                "SELECT COUNT(*) FROM optimization_run WHERE activity_plan_id = :planId AND status = 'FINISHED'")
                        .param("planId", planId).query(Integer.class).single()).isEqualTo(1));
        String runId = jdbcClient.sql("SELECT id FROM optimization_run WHERE activity_plan_id = :planId AND status = 'FINISHED'")
                .param("planId", planId).query(String.class).single();

        for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(planId)) {
            mockMvc.perform(get("/api/plans/" + planId + "/runs/" + runId + "/explanations/players/" + p.id())
                    .header("X-GP-Token", VALID_TOKEN));
        }
        for (var group : jdbcClient.sql("SELECT id FROM training_group WHERE activity_plan_id = :planId")
                .param("planId", planId).query((rs, n) -> rs.getString("id")).list()) {
            mockMvc.perform(get("/api/plans/" + planId + "/runs/" + runId + "/explanations/groups/" + group)
                    .header("X-GP-Token", VALID_TOKEN));
        }
        mockMvc.perform(get("/api/plans/" + planId + "/runs/" + runId + "/explanations/plan").header("X-GP-Token", VALID_TOKEN));

        List<Map<String, Object>> explanationRecordRows = jdbcClient.sql("SELECT * FROM explanation_record WHERE optimization_run_id = :runId")
                .param("runId", runId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "positive_factors_json", String.valueOf(rs.getString("positive_factors_json")),
                        "negative_factors_json", String.valueOf(rs.getString("negative_factors_json")),
                        "alternative_groups_json", String.valueOf(rs.getString("alternative_groups_json")),
                        "broken_preferences_json", String.valueOf(rs.getString("broken_preferences_json")),
                        "score_impact_json", String.valueOf(rs.getString("score_impact_json"))))
                .list();
        assertThat(explanationRecordRows).isNotEmpty();
        assertNoLeak(explanationRecordRows);

        String resultSummaryJson = jdbcClient.sql("SELECT result_summary_json FROM optimization_run WHERE id = :runId")
                .param("runId", runId).query(String.class).single();
        assertThat(resultSummaryJson).doesNotContain(SENSITIVE_IMPORTED_COMMENT).doesNotContain(SENSITIVE_INTERNAL_NOTE);
        assertThat(resultSummaryJson).contains("constraintSummaries"); // sanity: the new M7 field is actually populated

        // Defensive: confirm the raw column still holds the sensitive text.
        String rawComment = jdbcClient.sql("SELECT imported_comment FROM participant_profile WHERE id = :id")
                .param("id", someParticipant.id()).query(String.class).single();
        assertThat(rawComment).isEqualTo(SENSITIVE_IMPORTED_COMMENT);
    }

    private void assertNoLeak(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                String json = (String) value;
                assertThat(json).doesNotContain(SENSITIVE_IMPORTED_COMMENT);
                assertThat(json).doesNotContain(SENSITIVE_INTERNAL_NOTE);
            }
        }
    }
}
