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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MockMvc tests for {@code GET /api/plans/{planId}/field-definitions} — M1 scope only returns the
 * global standard fields (19 from spec §9.2 + {@code mustNotPlayWith} added in M6a, backend/docs
 * /m6a-notes.md = 20); per-plan custom fields arrive in M4 (docs/plan.md M1 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class FieldDefinitionControllerTest {

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

    @Test
    void listsNineteenStandardFieldsForAnExistingPlan() throws Exception {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, now, now));

        mockMvc.perform(get("/api/plans/" + plan.id() + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].key").value("rankingPoints"))
                // COLUMN-storage fields expose their participant_profile column mapping...
                .andExpect(jsonPath("$[0].storageKind").value("COLUMN"))
                .andExpect(jsonPath("$[0].columnName").value("ranking_points"))
                // ...CUSTOM-storage fields don't (canTimes is sort_order 6 -> index 5).
                .andExpect(jsonPath("$[5].key").value("canTimes"))
                .andExpect(jsonPath("$[5].storageKind").value("CUSTOM"))
                .andExpect(jsonPath("$[5].columnName").isEmpty());
    }

    @Test
    void listForUnknownPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/plans/some-plan/field-definitions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
