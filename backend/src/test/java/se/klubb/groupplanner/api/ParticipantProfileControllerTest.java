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
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MockMvc CRUD + error-shape tests for {@code /api/plans/{planId}/participants} and
 * {@code /api/participants/{id}} (docs/plan.md M1 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParticipantProfileControllerTest {

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

    @Autowired
    private PersonRepository personRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, now, now));
        return plan.id();
    }

    private String createPerson() {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Kalle", "Karlsson", null, null, null, null, true, false, null, now, now));
        return person.id();
    }

    @Test
    void createReadUpdateDeleteHappyPath() throws Exception {
        String planId = createPlan();
        String personId = createPerson();

        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, 550.0, "seriespel", null, null, null, null, null, "kommentar", null, null, null));

        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personId").value(personId))
                .andExpect(jsonPath("$.activityPlanId").value(planId))
                .andExpect(jsonPath("$.rankingPoints").value(550.0))
                .andExpect(jsonPath("$.waitlisted").value(false))
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/plans/" + planId + "/participants").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        String updateBody = objectMapper.writeValueAsString(new ParticipantProfileController.UpdateParticipantProfileRequest(
                null, null, null, null, null, null, null, null, null, null, true));
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitlisted").value(true));

        mockMvc.perform(delete("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void duplicateParticipantForSamePersonAndPlanReturns409WithErrorShape() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, null, null, null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // Same person_id + activity_plan_id violates UNIQUE(person_id, activity_plan_id) and must
        // surface as HTTP 409 with the API-wide {"error": ...} shape (ApiExceptionHandler).
        mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createUnderUnknownPlanReturns404() throws Exception {
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, null, null, null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/does-not-exist/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createWithUnknownPersonReturns400() throws Exception {
        String planId = createPlan();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                "does-not-exist", null, null, null, null, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getUnknownParticipantReturns404() throws Exception {
        mockMvc.perform(get("/api/participants/does-not-exist").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listParticipantsWithoutTokenReturns401() throws Exception {
        String planId = createPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/participants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
