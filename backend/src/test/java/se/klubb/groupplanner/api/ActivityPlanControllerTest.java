package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MockMvc CRUD + error-shape tests for {@code /api/seasons/{seasonId}/plans} and
 * {@code /api/plans/{id}} (docs/plan.md M1 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityPlanControllerTest {

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

    private String createSeason() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        return season.id();
    }

    @Test
    void createReadUpdateDeleteHappyPath() throws Exception {
        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", "beach", null, 10, 8, 12, 300.0));

        String response = mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Herr"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.seasonPlanId").value(seasonId))
                .andExpect(jsonPath("$.defaultLevelMin").value(300.0))
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/seasons/" + seasonId + "/plans").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(planId));

        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId));

        // Raw JSON on purpose: UpdateActivityPlanRequest is a presence-tracking bean (three-state
        // PATCH semantics - see patchDistinguishesAbsentNullAndValueForDefaults), so PATCH bodies
        // are written as the literal JSON a client would send, absent fields genuinely absent.
        String updateBody = "{\"status\": \"saved\", \"defaultLevelMin\": 450.0}";
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("saved"))
                .andExpect(jsonPath("$.defaultLevelMin").value(450.0)) // value -> set
                .andExpect(jsonPath("$.defaultGroupTargetSize").value(10)) // absent -> kept
                .andExpect(jsonPath("$.defaultGroupMinSize").value(8))
                .andExpect(jsonPath("$.defaultGroupMaxSize").value(12));

        mockMvc.perform(delete("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createUnderUnknownSeasonReturns404() throws Exception {
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, null, null, null));

        mockMvc.perform(post("/api/seasons/does-not-exist/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void defaultLevelMinOutOfRangeIsRejectedOnCreate() throws Exception {
        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, null, null, 1500.0));

        mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void groupSizeDefaultsOutOfOrderAreRejected() throws Exception {
        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, 10, 12, 8, null));

        mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * v0.3.0 review fix (Finding 2): a partially-set default must be validated against the
     * EFFECTIVE post-fallback triple. Only min=20 set -> effective target 10 / max 12 (the
     * GroupGenerator fallbacks) -> min 20 > max 12 would produce broken groups, so it is a 400 -
     * both on create and against the merged values on PATCH.
     */
    @Test
    void minSizeAloneAboveEffectiveFallbackSizesIsRejected() throws Exception {
        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, 20, null, null));

        mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("min=20, target=10, max=12")));

        // Same rule against the MERGED values on PATCH: create a plan without defaults, then patch
        // only defaultGroupMinSize above the fallback target/max.
        String validBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, null, null, null));
        String response = mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultGroupMinSize\": 20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * v0.3.0 review fix (Finding 1): PATCH is three-state per group-default field via Optional
     * record components (Jackson jdk8) - absent property keeps the existing value, explicit JSON
     * null clears it back to unset (previously impossible: once set, a default could never be
     * removed), and a value sets it. Asserted via follow-up GETs so the persisted state is what is
     * verified, not just the PATCH response.
     */
    @Test
    void patchDistinguishesAbsentNullAndValueForDefaults() throws Exception {
        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, 10, 8, 12, 300.0));
        String response = mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(response).get("id").asText();

        // 1) Absent -> keep: a patch that touches none of the defaults leaves all four intact.
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Herr 2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.name").value("Herr 2"))
                .andExpect(jsonPath("$.defaultGroupTargetSize").value(10))
                .andExpect(jsonPath("$.defaultGroupMinSize").value(8))
                .andExpect(jsonPath("$.defaultGroupMaxSize").value(12))
                .andExpect(jsonPath("$.defaultLevelMin").value(300.0));

        // 2) Explicit null -> clear: defaultLevelMin round-trips as unset; sizes untouched.
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultLevelMin\": null}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.defaultLevelMin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.defaultGroupTargetSize").value(10));

        // 3) Value -> set again.
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultLevelMin\": 250.5}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.defaultLevelMin").value(250.5));

        // Clearing all three sizes at once also persists (effective fallback triple 10/8/12 is valid).
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultGroupTargetSize\": null, \"defaultGroupMinSize\": null, \"defaultGroupMaxSize\": null}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.defaultGroupTargetSize").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.defaultGroupMinSize").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.defaultGroupMaxSize").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getUnknownPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listPlansWithoutTokenReturns401() throws Exception {
        String seasonId = createSeason();

        mockMvc.perform(get("/api/seasons/" + seasonId + "/plans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
