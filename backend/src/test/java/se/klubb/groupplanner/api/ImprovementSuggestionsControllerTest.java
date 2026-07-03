package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * HTTP-level smoke test for {@code GET /api/plans/{planId}/runs/{runId}/suggestions} (WI-D) —
 * verifies the endpoint is wired into {@link ExplanationController} with the same staleness
 * envelope/404 conventions as the sibling {@code .../explanations/*} endpoints; the suggestion
 * CONTENT itself (per-kind scenarios) is covered service-level by {@code
 * se.klubb.groupplanner.explain.ImprovementSuggestionServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImprovementSuggestionsControllerTest {

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
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "Herr", "draft", 5, 1, 5, null, now, now));
        return plan.id();
    }

    private String finishedRun(String planId) {
        Instant now = Instant.now();
        int revision = activityPlanRepository.getPlanRevision(planId);
        OptimizationRun run = new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", "0hard/0medium/0soft", OptimizationRun.STATUS_FINISHED,
                now.toString(), now.toString(), 0, "{}", revision);
        return optimizationRunRepository.insert(run).id();
    }

    @Test
    void returnsEmptySuggestionsWithStalenessEnvelopeForAFreshPlanWithNoGroups() throws Exception {
        String planId = createPlan();
        String runId = finishedRun(planId);

        mockMvc.perform(get("/api/plans/" + planId + "/runs/" + runId + "/suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.suggestions").isEmpty())
                .andExpect(jsonPath("$.omittedCount").value(0));
    }

    @Test
    void unknownPlanIdIs404() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/runs/also-missing/suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownRunIdWithinAKnownPlanIs404() throws Exception {
        String planId = createPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/runs/does-not-exist/suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }
}
