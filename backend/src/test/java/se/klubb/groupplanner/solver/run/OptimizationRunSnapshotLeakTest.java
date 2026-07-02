package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
 * Extends CLAUDE.md's comment-leak protection (docs/plan.md "Privacy corrections": "Automated leak
 * test: after full import->solve->save cycle, assert no comment text appears in any {@code *_json}
 * column or solver input") to the M6a attack surface: {@code optimization_run.input_snapshot_json}/
 * {@code result_summary_json}. Sets deliberately distinctive {@code importedComment}/{@code
 * internalNote} text on a real participant, runs a full solve through the real {@code
 * SolveController} endpoint, and asserts — via a raw {@link JdbcClient} query against the actual DB
 * column, not the domain mapping (which could mask a query bug) — that neither sensitive string
 * appears anywhere in either JSON column, for every run persisted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class OptimizationRunSnapshotLeakTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String SENSITIVE_IMPORTED_COMMENT = "KÄNSLIG-ANMÄLNINGSKOMMENTAR-hemlig-diagnos-xyz789";
    private static final String SENSITIVE_INTERNAL_NOTE = "KÄNSLIG-INTERN-ANTECKNING-styrelsebeslut-abc123";

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
    void solverRunSnapshotsNeverContainImportedCommentOrInternalNoteText() throws Exception {
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
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"FAST\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Integer finished = jdbcClient.sql(
                            "SELECT COUNT(*) FROM optimization_run WHERE activity_plan_id = :planId AND status = 'FINISHED'")
                    .param("planId", planId)
                    .query(Integer.class)
                    .single();
            assertThat(finished).isEqualTo(1);
        });

        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT input_snapshot_json, constraint_weights_json, result_summary_json "
                                + "FROM optimization_run WHERE activity_plan_id = :planId")
                .param("planId", planId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "input_snapshot_json", String.valueOf(rs.getString("input_snapshot_json")),
                        "constraint_weights_json", String.valueOf(rs.getString("constraint_weights_json")),
                        "result_summary_json", String.valueOf(rs.getString("result_summary_json"))))
                .list();
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                String json = (String) value;
                assertThat(json).doesNotContain(SENSITIVE_IMPORTED_COMMENT);
                assertThat(json).doesNotContain(SENSITIVE_INTERNAL_NOTE);
            }
        }

        // Defensive: also confirm the raw column still holds the sensitive text (i.e. this test
        // would actually catch a leak, not pass vacuously because the comment was never persisted).
        String rawComment = jdbcClient.sql("SELECT imported_comment FROM participant_profile WHERE id = :id")
                .param("id", someParticipant.id())
                .query(String.class)
                .single();
        assertThat(rawComment).isEqualTo(SENSITIVE_IMPORTED_COMMENT);
    }
}
