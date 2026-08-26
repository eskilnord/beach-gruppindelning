package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc tests for {@code GET|PUT /api/app-settings} (v0.6.0 B3): persistence across GET after a
 * PUT, input normalization, and the Swedish 400 for every invalid-body shape. All test methods
 * share one DB (one Spring context, keyed by the static {@code @TempDir} - same convention as
 * {@code DefaultVenueAutoCreationTest}), but every test is written to be self-contained and order-
 * independent: each test that needs a known starting value PUTs it explicitly first, rather than
 * relying on another test having (or not having) run yet. In particular, this class deliberately
 * does NOT assert "GET with literally no prior PUT returns SIMPLE" - that would depend on either
 * test-execution order (nothing else in this class has PUT yet) or on
 * AppSettingsController.get()'s {@code .orElse(DEFAULT_UI_MODE)} fallback, which the V12 migration
 * seed makes unreachable in practice (a {@code ui.mode} row always exists once V12 has run) - that
 * migration-seeded-default behavior is covered honestly by {@code
 * FlywayMigrationTest#appSettingSeedsUiModeSimpleByDefault} instead. The {@code .orElse(...)}
 * fallback itself is kept as defensive code (e.g. a hand-edited/corrupted DB missing the row), just
 * not claimed as tested here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppSettingsControllerTest {

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

    @Test
    void putSimpleThenGetReturnsSimplePersisted() throws Exception {
        String putBody = objectMapper.writeValueAsString(new AppSettingsController.UpdateAppSettingsRequest("SIMPLE"));

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("SIMPLE"));

        mockMvc.perform(get("/api/app-settings").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("SIMPLE"));
    }

    @Test
    void putAdvancedThenGetReturnsAdvancedPersisted() throws Exception {
        String putBody = objectMapper.writeValueAsString(new AppSettingsController.UpdateAppSettingsRequest("ADVANCED"));

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("ADVANCED"));

        mockMvc.perform(get("/api/app-settings").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("ADVANCED"));
    }

    /** Fix 4 (MINOR): lowercase/whitespace-padded values are trimmed + uppercased before validation. */
    @Test
    void putLowercaseAndPaddedUiModeIsNormalizedAndAccepted() throws Exception {
        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uiMode\": \"simple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("SIMPLE"));

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uiMode\": \" ADVANCED \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("ADVANCED"));

        mockMvc.perform(get("/api/app-settings").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value("ADVANCED"));
    }

    @Test
    void putInvalidUiModeReturns400WithSwedishMessageAndLeavesStateUnchanged() throws Exception {
        putKnownValue("ADVANCED");

        String putBody = objectMapper.writeValueAsString(new AppSettingsController.UpdateAppSettingsRequest("EXTREME"));
        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED."));

        assertStoredValueIs("ADVANCED");
    }

    /**
     * Fix 1 (BLOCKER): {@code {"uiMode": null}} - the request body deserializes fine (not the
     * missing-body case below), but {@code request.uiMode()} is null. Before the fix,
     * {@code Set.of(...).contains(null)} threw NPE -> 500; must be the same Swedish 400.
     */
    @Test
    void putNullUiModeReturns400WithSwedishMessageAndLeavesStateUnchanged() throws Exception {
        putKnownValue("SIMPLE");

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uiMode\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED."));

        assertStoredValueIs("SIMPLE");
    }

    /**
     * Fix 1 (BLOCKER): {@code {}} - uiMode is absent, so the deserialized record's {@code uiMode()}
     * is null, same as an explicit JSON null. Must not NPE, must be the same Swedish 400.
     */
    @Test
    void putEmptyJsonObjectReturns400WithSwedishMessageAndLeavesStateUnchanged() throws Exception {
        putKnownValue("ADVANCED");

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED."));

        assertStoredValueIs("ADVANCED");
    }

    /**
     * Fix 1 (BLOCKER): a completely empty request body. {@code @RequestBody} defaults to
     * {@code required = true}, so without the fix Spring raises {@code
     * HttpMessageNotReadableException} ("Malformed request body") before the controller's own
     * validation ever runs - {@code required = false} on the parameter lets {@code request} come
     * through as {@code null} instead, so this hits the same Swedish 400 as every other invalid
     * shape.
     */
    @Test
    void putEmptyBodyReturns400WithSwedishMessageAndLeavesStateUnchanged() throws Exception {
        putKnownValue("SIMPLE");

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED."));

        assertStoredValueIs("SIMPLE");
    }

    /**
     * Fix 1 (BLOCKER): a request body that is the literal JSON {@code null} (not empty, not an
     * object) - Jackson deserializes this to a null {@code request}, exercising the same
     * {@code required = false} path as the empty-body case but via a different wire shape.
     */
    @Test
    void putJsonNullBodyReturns400WithSwedishMessageAndLeavesStateUnchanged() throws Exception {
        putKnownValue("ADVANCED");

        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED."));

        assertStoredValueIs("ADVANCED");
    }

    @Test
    void getWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/app-settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    private void putKnownValue(String uiMode) throws Exception {
        String putBody = objectMapper.writeValueAsString(new AppSettingsController.UpdateAppSettingsRequest(uiMode));
        mockMvc.perform(put("/api/app-settings")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk());
    }

    private void assertStoredValueIs(String expectedUiMode) throws Exception {
        mockMvc.perform(get("/api/app-settings").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiMode").value(expectedUiMode));
    }
}
