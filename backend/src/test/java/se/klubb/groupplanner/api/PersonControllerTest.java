package se.klubb.groupplanner.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * MockMvc CRUD + error-shape tests for {@code /api/persons} (docs/plan.md M1 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PersonControllerTest {

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
    void createReadUpdateDeleteHappyPath() throws Exception {
        String createBody = objectMapper.writeValueAsString(new PersonController.CreatePersonRequest(
                "Kalle", "Karlsson", null, "kalle@example.com", null, null, null, null, null));

        String response = mockMvc.perform(post("/api/persons")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstName").value("Kalle"))
                .andExpect(jsonPath("$.canBeParticipant").value(true))
                .andExpect(jsonPath("$.canBeCoach").value(false))
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/persons/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Karlsson"));

        mockMvc.perform(get("/api/persons").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));

        String updateBody = objectMapper.writeValueAsString(new PersonController.UpdatePersonRequest(
                null, null, "Kalle K", null, null, null, null, true, null));
        mockMvc.perform(patch("/api/persons/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Kalle K"))
                .andExpect(jsonPath("$.canBeCoach").value(true));

        mockMvc.perform(delete("/api/persons/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/persons/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void duplicateExternalIdReturns409WithErrorShape() throws Exception {
        String createBody = objectMapper.writeValueAsString(new PersonController.CreatePersonRequest(
                "Anna", "Andersson", null, null, null, "member-4711", null, null, null));

        mockMvc.perform(post("/api/persons")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // person.external_id is UNIQUE; a second person with the same externalId must surface as
        // HTTP 409 with the API-wide {"error": ...} shape (ApiExceptionHandler).
        String duplicateBody = objectMapper.writeValueAsString(new PersonController.CreatePersonRequest(
                "Annan", "Person", null, null, null, "member-4711", null, null, null));
        mockMvc.perform(post("/api/persons")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createWithoutLastNameReturns400() throws Exception {
        mockMvc.perform(post("/api/persons")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Kalle\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getUnknownPersonReturns404() throws Exception {
        mockMvc.perform(get("/api/persons/does-not-exist").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/persons").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
