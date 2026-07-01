package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc tests for the read-only {@code GET /api/constraint-definitions} listing (spec §10,
 * seeded by V2) — docs/plan.md M1 row.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstraintDefinitionControllerTest {

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
    void listsAllTwentyFourSeededConstraints() throws Exception {
        String body = mockMvc.perform(get("/api/constraint-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> constraints = objectMapper.readValue(body, List.class);
        Map<String, Object> sameGroupSoft = constraints.stream()
                .filter(c -> "sameGroupSoft".equals(c.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(sameGroupSoft.get("defaultWeight")).isEqualTo(80);
        assertThat(sameGroupSoft.get("hardOrSoft")).isEqualTo("SOFT");
    }

    @Test
    void listWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/constraint-definitions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
