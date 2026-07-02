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

        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"waitlisted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitlisted").value(true))
                // Fields omitted from the PATCH body must be left untouched.
                .andExpect(jsonPath("$.rankingPoints").value(550.0))
                .andExpect(jsonPath("$.importedComment").value("kommentar"));

        mockMvc.perform(delete("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * The M1 review finding this milestone fixes: a plain "null means keep" PATCH DTO can never
     * clear a nullable column (e.g. a comment) because there is no way to distinguish "field
     * omitted" from "field explicitly null" in a record/JSON-creator binding. The fix binds the
     * PATCH body as a raw {@code Map<String, JsonNode>} instead (see
     * {@code ParticipantProfileController} class Javadoc) - this test asserts both halves of the
     * distinction actually work.
     */
    @Test
    void patchDistinguishesAbsentFieldFromExplicitNull() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, 550.0, "seriespel", "Grupp 3", 7.0, null, null, 600.0, "kommentar", "intern anteckning", null, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        // Omitted field: importedComment/internalNote/rankingPoints must survive untouched.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previousGroupName\": \"Grupp 4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousGroupName").value("Grupp 4"))
                .andExpect(jsonPath("$.importedComment").value("kommentar"))
                .andExpect(jsonPath("$.internalNote").value("intern anteckning"))
                .andExpect(jsonPath("$.rankingPoints").value(550.0));

        // Explicit null: importedComment (the sensitive comment field, spec §21.2) must actually clear.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"importedComment\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedComment").doesNotExist())
                // Sibling fields untouched by an unrelated explicit-null.
                .andExpect(jsonPath("$.internalNote").value("intern anteckning"))
                .andExpect(jsonPath("$.previousGroupName").value("Grupp 4"));

        // Explicit null on a nullable numeric column also clears.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualLevelScore\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualLevelScore").doesNotExist());
    }

    @Test
    void patchCannotClearRequiredBooleanColumnsWithExplicitNull() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, null, null, null, null, null, null, null, null, null, null, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"waitlisted\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * M4 review finding: silently ignoring unknown PATCH keys is a privacy foot-gun — a typo like
     * {@code "imortedComment": null} would return 200 while clearing nothing (the §21.2 comment the
     * user believed they deleted is still in the database). Unknown keys must be a 400.
     */
    @Test
    void patchRejectsUnknownKeysWith400() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, null, null, null, null, null, null, null, "känslig kommentar", null, null, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        // The typo'd clear attempt must fail loudly, not silently succeed at nothing.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imortedComment\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("imortedComment")));

        // And a mixed body (one valid, one unknown key) is rejected atomically - nothing changes.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"waitlisted\": true, \"notAField\": 1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.waitlisted").value(false))
                .andExpect(jsonPath("$.importedComment").value("känslig kommentar"));
    }

    /**
     * M4 review finding: {@code importedComment} is the audit trail of what the member actually
     * wrote at registration (spec §2.2/§8.5 - only the import writes it). The generic PATCH may
     * only CLEAR it (spec §21.2); writing arbitrary text is rejected. {@code internalNote} (the
     * council's own note) stays freely writable.
     */
    @Test
    void patchAllowsClearingButNotWritingImportedComment() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, null, null, null, null, null, null, null, "original kommentar", null, null, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        // Writing text into importedComment -> 400, value untouched.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"importedComment\": \"påhittad kommentar\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.importedComment").value("original kommentar"));

        // internalNote is freely writable.
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internalNote\": \"kansliets egen anteckning\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalNote").value("kansliets egen anteckning"));

        // Clearing importedComment with explicit null still works (spec §21.2).
        mockMvc.perform(patch("/api/participants/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"importedComment\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedComment").doesNotExist());
    }

    @Test
    void recomputeLevelsEndpointPersistsEstimatedLevelForWholePlan() throws Exception {
        String planId = createPlan();
        String personId = createPerson();
        String createBody = objectMapper.writeValueAsString(new ParticipantProfileController.CreateParticipantProfileRequest(
                personId, 720.0, "seriespel", null, null, null, null, null, null, null, null, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post("/api/plans/" + planId + "/participants/recompute-levels")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recomputedCount").value(1));

        mockMvc.perform(get("/api/participants/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedLevel").value(720.0))
                .andExpect(jsonPath("$.levelConfidence").value(0.6));
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
