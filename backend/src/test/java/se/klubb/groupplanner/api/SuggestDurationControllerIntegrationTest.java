package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.OptimizationRun;
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
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.testsupport.ActiveSolveCleanup;

/**
 * v0.2.0 (SUGGESTED OPTIMIZATION TIME): {@code POST .../solve/suggest-duration} end-to-end (sane
 * values against the real {@code large-120} fixture, 409-while-solving, cross-dataset monotonicity,
 * benchmark caching) and the {@code CUSTOM} solve profile end-to-end (small duration on {@code
 * small-10}, plus the 10..900 validation bounds). See {@code SuggestDurationServiceTest} for the
 * pure-formula monotonicity coverage this complements at the REST layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(ActiveSolveCleanup.class)
class SuggestDurationControllerIntegrationTest {

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
    @Autowired
    private SolveCoordinator solveCoordinator;

    private String loadDataset(String name) {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load(name);
    }

    private JsonNode suggest(String planId) throws Exception {
        String response = mockMvc.perform(post("/api/plans/" + planId + "/solve/suggest-duration")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void suggestDurationOnLargePlanReturnsSaneValues() throws Exception {
        String planId = loadDataset("large-120");

        JsonNode json = suggest(planId);

        int suggestedSeconds = json.get("suggestedSeconds").asInt();
        assertThat(suggestedSeconds).isBetween(15, 600);
        assertThat(java.util.List.of(15, 30, 60, 90, 120, 180, 240, 300, 420, 600)).contains(suggestedSeconds);
        assertThat(json.get("machineSpeedFactor").asDouble()).isGreaterThan(0.0);
        assertThat(json.get("benchmarkMs").asLong()).isGreaterThan(0L);

        JsonNode size = json.get("problemSize");
        assertThat(size.get("participants").asInt()).isEqualTo(130); // large-120's README: actually 130 participants
        assertThat(size.get("groups").asInt()).isEqualTo(12);
        assertThat(size.get("activeBlocks").asInt()).isEqualTo(12);
        assertThat(size.get("coaches").asInt()).isEqualTo(8);
        assertThat(size.get("wishes").asInt()).isGreaterThan(0);
        assertThat(size.get("customFieldConstraints").asInt()).isGreaterThan(0);

        String rationale = json.get("rationaleSv").asText();
        assertThat(rationale).contains("130 spelare").contains("12 grupper").contains("sekunder").endsWith(".");
    }

    @Test
    void suggestDurationReturns409WhileASolveIsActiveForThatPlan() throws Exception {
        String planId = loadDataset("small-10");
        // THOROUGH (120s) background solve, cleaned up by ActiveSolveCleanup after this test.
        solveCoordinator.startSolve(planId, SolveProfile.THOROUGH);

        mockMvc.perform(post("/api/plans/" + planId + "/solve/suggest-duration").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());
    }

    /** REST-level companion to {@code SuggestDurationServiceTest}'s pure-formula monotonicity
     * coverage: a genuinely bigger real plan ({@code large-120}) must never suggest FEWER seconds
     * than a genuinely smaller one ({@code small-10}). */
    @Test
    void aBiggerRealPlanNeverSuggestsFewerSecondsThanASmallerOne() throws Exception {
        String smallPlanId = loadDataset("small-10");
        String largePlanId = loadDataset("large-120");

        int smallSeconds = suggest(smallPlanId).get("suggestedSeconds").asInt();
        int largeSeconds = suggest(largePlanId).get("suggestedSeconds").asInt();

        assertThat(largeSeconds).isGreaterThanOrEqualTo(smallSeconds);
    }

    /** The hardware benchmark is a one-time, app-session-cached measurement (task spec: "Cache the
     * benchmark result in-memory for the app session"): repeated calls — even across different plans —
     * must reuse the SAME {@code benchmarkMs}/{@code machineSpeedFactor}, and {@code suggestedSeconds}
     * for the SAME plan must be stable call-to-call (the task's own determinism note). */
    @Test
    void theHardwareBenchmarkIsCachedAcrossCallsAndPlans() throws Exception {
        String planId = loadDataset("small-10");
        String otherPlanId = loadDataset("large-120");

        JsonNode first = suggest(planId);
        JsonNode second = suggest(planId);
        JsonNode third = suggest(otherPlanId);

        assertThat(second.get("suggestedSeconds").asInt()).isEqualTo(first.get("suggestedSeconds").asInt());
        assertThat(second.get("benchmarkMs").asLong()).isEqualTo(first.get("benchmarkMs").asLong());
        assertThat(second.get("machineSpeedFactor").asDouble()).isEqualTo(first.get("machineSpeedFactor").asDouble());
        assertThat(third.get("benchmarkMs").asLong()).isEqualTo(first.get("benchmarkMs").asLong());
        assertThat(third.get("machineSpeedFactor").asDouble()).isEqualTo(first.get("machineSpeedFactor").asDouble());
    }

    @Test
    void customProfileSolvesEndToEndWithASmallDurationOnSmallTen() throws Exception {
        String planId = loadDataset("small-10");

        String startResponse = mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"durationSeconds\":15}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(startResponse).get("runId").asText();

        // Termination is min(15s spentLimit, 7s unimprovedSpentLimit) - generous margin for CI.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OptimizationRun polled = optimizationRunRepository.findById(runId).orElseThrow();
            assertThat(polled.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        OptimizationRun run = optimizationRunRepository.findById(runId).orElseThrow();
        assertThat(run.score()).startsWith("0hard");
        assertThat(run.durationMs()).isLessThan(20_000); // well inside the 15s spentLimit + startup slack
    }

    @Test
    void customProfileWithoutDurationSecondsReturns400() throws Exception {
        String planId = loadDataset("small-10");

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customProfileDurationBelowMinimumReturns400() throws Exception {
        String planId = loadDataset("small-10");

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"durationSeconds\":9}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customProfileDurationAboveMaximumReturns400() throws Exception {
        String planId = loadDataset("small-10");

        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"durationSeconds\":901}"))
                .andExpect(status().isBadRequest());
    }
}
