package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.util.Uuid7;

/**
 * WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause C) unchanged-result
 * detection: {@code resultSummaryJson.unchangedFromPrevious} on {@code OptimizationRunService
 * #finishRun}. Uses the synchronous GREEDY profile (deterministic, no {@code ActiveSolveCleanup}
 * needed per its own javadoc — the run row is terminal before the HTTP response returns) rather
 * than a wall-clock profile, so "identical input" doesn't depend on the solver's own convergence
 * being bit-identical across two separate runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnchangedResultDetectionIntegrationTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    private OptimizationRunRepository optimizationRunRepository;

    private String loadSmallPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    private JsonNode runGreedyAndReturnSummary(String planId) throws Exception {
        String response = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"GREEDY\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(response).get("runId").asText();
        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        return objectMapper.readTree(run.resultSummaryJson());
    }

    private static boolean unchangedFlag(JsonNode summary) {
        return summary.has("unchangedFromPrevious") && summary.get("unchangedFromPrevious").asBoolean();
    }

    /** Inserts one extra participant (with an "awaiting placement" player_assignment row, matching
     *  {@code ParticipantProfileController#create}'s own behavior) directly via the repositories -
     *  mirrors {@code GroupGeneratorTest#addParticipants}' pattern of setting {@code estimatedLevel}
     *  directly rather than going through {@code LevelService}. */
    private void addExtraParticipant(String planId, double level) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Extra", "Deltagare", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, level, 1.0, null, null, null, false, false));
        playerAssignmentRepository.insertImportedIfAbsent(profile.id());
    }

    @Test
    void firstGreedyRunOnAFreshPlanIsNeverUnchangedFromPrevious() throws Exception {
        String planId = loadSmallPlan();

        JsonNode summary = runGreedyAndReturnSummary(planId);

        assertThat(unchangedFlag(summary)).isFalse();
    }

    @Test
    void secondGreedyRunOnIdenticalInputIsUnchangedFromPrevious() throws Exception {
        String planId = loadSmallPlan();
        runGreedyAndReturnSummary(planId); // establishes the baseline FINISHED run.

        JsonNode second = runGreedyAndReturnSummary(planId);

        assertThat(unchangedFlag(second)).isTrue();
    }

    /**
     * Adds one extra participant between the two runs - greedy re-sorts and re-slices ALL active
     * participants evenly across the plan's (unchanged) 2 groups, so growing the roster from 10 to
     * 11 shifts the slice sizes from 5/5 to 6/5 (base = n/groupCount, remainder = n%groupCount): at
     * least one participant's computed group differs between the pre-run-2 snapshot (still the old
     * 10-participant result, plus the new participant's fresh {@code group_id = null} row) and the
     * post-run-2 writeback. A deliberately different scenario from the two tests above: this one
     * changes what the SOLVE ITSELF computes (root cause A/the general "input changed" case), not
     * just plan state that happens to be set outside the solve.
     */
    @Test
    void addingAParticipantBetweenRunsIsNotUnchangedFromPrevious() throws Exception {
        String planId = loadSmallPlan();
        runGreedyAndReturnSummary(planId);

        addExtraParticipant(planId, 999.0);

        JsonNode second = runGreedyAndReturnSummary(planId);

        assertThat(unchangedFlag(second)).isFalse();
    }
}
