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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/** {@code GET /api/seasons/{seasonId}/conflicts} (docs/design/04-solver.md §14.2). Full coverage
 * (real cross-plan overlaps) lives in {@code season.ConflictServiceTest}; this is the controller
 * wiring smoke test (auth, 404, empty-season shape). */
@SpringBootTest
@AutoConfigureMockMvc
class SeasonConflictControllerTest {

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

    @Test
    void emptySeasonReturnsAnEmptyConflictList() throws Exception {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));

        mockMvc.perform(get("/api/seasons/" + season.id() + "/conflicts").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownSeasonReturns404() throws Exception {
        mockMvc.perform(get("/api/seasons/does-not-exist/conflicts").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/seasons/anything/conflicts"))
                .andExpect(status().isUnauthorized());
    }
}
