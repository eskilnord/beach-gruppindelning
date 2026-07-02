package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Custom field value round-trips per field type (spec §7.14, §9.1 structured-entry drawer):
 * {@code GET|PUT /api/plans/{planId}/participants/{pid}/field-values}, including relation-id
 * validation (personRelation) and real {@code time_slot} id validation (timeRelation — M6a,
 * backend/docs/m6a-notes.md; previously free-text SwedishTimeParser-grammar validated before
 * structured TimeSlot CRUD existed).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParticipantFieldValueControllerTest {

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
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;

    private String createTimeSlot(String planId, String startTime, String endTime) {
        TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                Uuid7.generate(), planId, "THURSDAY", null, startTime, endTime, null, "Torsdag " + startTime));
        return slot.id();
    }

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, now, now));
        return plan.id();
    }

    private String createParticipant(String planId, String firstName) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, "Testsson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, null, null, null, null, false, false));
        return profile.id();
    }

    private String createCustomField(String planId, String key, String fieldType, boolean affectsOptimization,
            String constraintType, String hardOrSoft, Integer weight, String optionsJson) throws Exception {
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                key, key, fieldType, affectsOptimization, constraintType, hardOrSoft, weight, null, null, optionsJson, null));
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void textNumberBooleanRoundTripAndClearViaExplicitNull() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna");
        createCustomField(planId, "shirtSize", "text", false, null, null, null, null);
        createCustomField(planId, "shoeSize", "number", false, null, null, null, null);
        createCustomField(planId, "vegetarian", "boolean", false, null, null, null, null);

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shirtSize\": \"M\", \"shoeSize\": 42, \"vegetarian\": true}"))
                .andExpect(status().isOk());

        String getResponse = mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode values = objectMapper.readTree(getResponse);
        assertThat(valueFor(values, "shirtSize").asText()).isEqualTo("M");
        assertThat(valueFor(values, "shoeSize").asInt()).isEqualTo(42);
        assertThat(valueFor(values, "vegetarian").asBoolean()).isTrue();

        // Explicit null clears.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shirtSize\": null}"))
                .andExpect(status().isOk());
        String afterClear = mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        assertThat(valueFor(objectMapper.readTree(afterClear), "shirtSize").isNull()).isTrue();
    }

    @Test
    void personRelationValidatesReferencedParticipantsExistInPlanAndRejectsSelfReference() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna");
        String friendId = createParticipant(planId, "Bertil");
        createCustomField(planId, "wantsToPlayWith", "personRelation", true, "SAME_GROUP", "SOFT", 80, null);

        // Happy path: a real participant id in the same plan.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantsToPlayWith\": [\"" + friendId + "\"]}"))
                .andExpect(status().isOk());
        String getResponse = mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        JsonNode value = valueFor(objectMapper.readTree(getResponse), "wantsToPlayWith");
        assertThat(value.get(0).asText()).isEqualTo(friendId);

        // Unknown id in the same plan -> 400.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantsToPlayWith\": [\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // Self-reference -> 400.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantsToPlayWith\": [\"" + pid + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // A participant from a different plan -> 400.
        String otherPlanId = createPlan();
        String otherPlanParticipant = createParticipant(otherPlanId, "Utomstaende");
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantsToPlayWith\": [\"" + otherPlanParticipant + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void timeRelationValidatesEachIdAgainstRealTimeSlotsInThePlan() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna");
        String slot1 = createTimeSlot(planId, "18:00", "19:30");
        String slot2 = createTimeSlot(planId, "19:30", "21:00");
        createCustomField(planId, "cannotTimesCustom", "timeRelation", true, "TIME_AVAILABILITY", "HARD", null, null);

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cannotTimesCustom\": [\"" + slot1 + "\", \"" + slot2 + "\"]}"))
                .andExpect(status().isOk());
        String getResponse = mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        JsonNode value = valueFor(objectMapper.readTree(getResponse), "cannotTimesCustom");
        assertThat(value.get(0).asText()).isEqualTo(slot1);

        // Unknown id -> 400.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cannotTimesCustom\": [\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // A time slot from a different plan -> 400.
        String otherPlanId = createPlan();
        String otherPlanSlot = createTimeSlot(otherPlanId, "18:00", "19:30");
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cannotTimesCustom\": [\"" + otherPlanSlot + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void singleSelectValidatesValueAgainstDeclaredOptions() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna");
        createCustomField(planId, "tshirtSize", "singleSelect", false, null, null, null, "[\"S\",\"M\",\"L\"]");

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tshirtSize\": \"M\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tshirtSize\": \"XXL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void columnStorageFieldCannotBeWrittenViaFieldValuesEndpoint() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna");

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + pid + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rankingPoints\": 500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getForUnknownParticipantReturns404() throws Exception {
        String planId = createPlan();
        mockMvc.perform(get("/api/plans/" + planId + "/participants/does-not-exist/field-values")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    private static JsonNode valueFor(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.get("key").asText())) {
                return node.get("value");
            }
        }
        throw new AssertionError("No field-value entry for key " + key);
    }
}
