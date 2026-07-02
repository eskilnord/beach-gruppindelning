package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WI-4: {@code POST /api/demo} is the frontend's "Prova med demodata" entry point (docs/design's
 * house style — see {@code PersonControllerTest} for the same MockMvc + {@code X-GP-Token} pattern).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoDataControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createReturnsSeasonAndPlanIds() throws Exception {
        mockMvc.perform(post("/api/demo").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seasonId").isNotEmpty())
                .andExpect(jsonPath("$.planId").isNotEmpty());
    }

    @Test
    void requiresAuthToken() throws Exception {
        mockMvc.perform(post("/api/demo")).andExpect(status().isUnauthorized());
    }
}
