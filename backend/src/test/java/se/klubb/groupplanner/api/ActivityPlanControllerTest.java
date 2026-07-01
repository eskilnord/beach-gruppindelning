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
                new ActivityPlanController.CreateActivityPlanRequest("Herr", "beach", null, 10, 8, 12));

        String response = mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Herr"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.seasonPlanId").value(seasonId))
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/seasons/" + seasonId + "/plans").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(planId));

        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId));

        String updateBody = objectMapper.writeValueAsString(
                new ActivityPlanController.UpdateActivityPlanRequest(null, null, "saved", null, null, null));
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("saved"));

        mockMvc.perform(delete("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/plans/" + planId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createUnderUnknownSeasonReturns404() throws Exception {
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, null, null));

        mockMvc.perform(post("/api/seasons/does-not-exist/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
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
