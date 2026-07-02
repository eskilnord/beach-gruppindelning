package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.fields.ConstraintWeightOverrideRequest;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Per-plan constraint weight overrides (spec §9.4/§7.16, docs/plan.md M4 row): merge logic (unset
 * rows fall back to {@code constraint_definition} defaults, set rows override), HARD/SOFT-only
 * reclassification guardrail (ADR-006).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstraintWeightControllerTest {

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

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    @Test
    void listReturnsAllThirtyTwoConstraintsWithDefaultsAndNoneOverridden() throws Exception {
        String planId = createPlan();

        // 24 from spec §10.1-10.24 + 7 M6a additions + 1 M6b addition (coachPreferredTimeSlot, V6).
        mockMvc.perform(get("/api/plans/" + planId + "/constraint-weights").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(32))
                .andExpect(jsonPath("$[?(@.key=='sameGroupSoft')].weight").value(80))
                .andExpect(jsonPath("$[?(@.key=='sameGroupSoft')].overridden").value(false));
    }

    @Test
    void putOverridesOneConstraintAndLeavesOthersAtDefault() throws Exception {
        String planId = createPlan();

        String body = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, 95, null)));
        String response = mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        JsonNode overridden = findByKey(json, "sameGroupSoft");
        assertWeight(overridden, 95, true, "SOFT");

        JsonNode untouched = findByKey(json, "levelBalance");
        assertWeight(untouched, 100, false, "SOFT");

        // GET reflects the same merged state.
        mockMvc.perform(get("/api/plans/" + planId + "/constraint-weights").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='sameGroupSoft')].weight").value(95));
    }

    @Test
    void reclassifiesHardToSoftAndBackWithinTheAllowedPair() throws Exception {
        String planId = createPlan();

        String toSoft = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("groupMaxSizeHard", "SOFT", 40, null)));
        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toSoft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='groupMaxSizeHard')].hardOrSoft").value("SOFT"));
    }

    @Test
    void rejectsMediumReclassification() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", "MEDIUM", 80, null)));

        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsWeightBelowOne() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, 0, null)));

        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * M6a review fix 5: the solver's score overflow-headroom analysis (ScoreHeadroomTest,
     * docs/design/04-solver.md §3.4) assumes a max UI weight of {@code WeightLimits.MAX_WEIGHT} —
     * the write path must actually enforce that ceiling or the analysis is fiction.
     */
    @Test
    void rejectsWeightAboveTenThousandButAcceptsExactlyTenThousand() throws Exception {
        String planId = createPlan();

        String tooBig = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, se.klubb.groupplanner.fields.WeightLimits.MAX_WEIGHT + 1, null)));
        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooBig))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        String atCeiling = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, se.klubb.groupplanner.fields.WeightLimits.MAX_WEIGHT, null)));
        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(atCeiling))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='sameGroupSoft')].weight").value(se.klubb.groupplanner.fields.WeightLimits.MAX_WEIGHT));
    }

    /**
     * M4 review finding: structural feasibility constraints (double-bookings, capacity, locks,
     * availability) must never be disableable — a plan solved without them can be physically
     * impossible to run. Quality/preference constraints stay freely disableable per spec §9.
     */
    @Test
    void structuralHardConstraintsCannotBeDisabledButQualityConstraintsCan() throws Exception {
        String planId = createPlan();

        // Blocked: playerNoOverlap (a player in two groups at the same time is physically impossible).
        String blocked = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("playerNoOverlap", null, null, false)));
        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blocked))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("playerNoOverlap")));

        // Still enabled after the rejected attempt.
        mockMvc.perform(get("/api/plans/" + planId + "/constraint-weights").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$[?(@.key=='playerNoOverlap')].enabled").value(true));

        // Allowed: lateTimeForLowerGroups is a policy preference - disabling it is a normal choice.
        String allowed = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("lateTimeForLowerGroups", null, null, false)));
        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allowed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='lateTimeForLowerGroups')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.key=='lateTimeForLowerGroups')].overridden").value(true));
    }

    @Test
    void rejectsUnknownConstraintKey() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                List.of(new ConstraintWeightOverrideRequest("notARealConstraint", "SOFT", 10, null)));

        mockMvc.perform(put("/api/plans/" + planId + "/constraint-weights")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listForUnknownPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/constraint-weights").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    private static JsonNode findByKey(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.get("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("No constraint-weight entry for key " + key);
    }

    private static void assertWeight(JsonNode node, int weight, boolean overridden, String hardOrSoft) {
        org.assertj.core.api.Assertions.assertThat(node.get("weight").asInt()).isEqualTo(weight);
        org.assertj.core.api.Assertions.assertThat(node.get("overridden").asBoolean()).isEqualTo(overridden);
        org.assertj.core.api.Assertions.assertThat(node.get("hardOrSoft").asText()).isEqualTo(hardOrSoft);
    }
}
