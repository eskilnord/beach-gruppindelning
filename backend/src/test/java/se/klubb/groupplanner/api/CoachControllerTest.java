package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
 * MockMvc CRUD + validation tests for {@code /api/plans/{planId}/coaches} and coach availability
 * (spec §13.1, docs/plan.md M5 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoachControllerTest {

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
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, null, now, now));
        return plan.id();
    }

    private String createSlot(String planId, String start, String end) throws Exception {
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, start, end, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void createFromNewPersonSetsCanBeCoachOnPerson() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", "anna@example.se", null, 650.0, 300.0, 900.0, 2, 4, false, "kommentar"));

        String response = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coachLevel").value(650.0))
                .andExpect(jsonPath("$.activityPlanId").value(planId))
                .andReturn().getResponse().getContentAsString();
        String personId = objectMapper.readTree(response).get("personId").asText();

        Person person = personRepository.findById(personId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(person.canBeCoach()).isTrue();
        org.assertj.core.api.Assertions.assertThat(person.firstName()).isEqualTo("Anna");
    }

    @Test
    void createFromExistingPersonFlipsCanBeCoachOnAndAllowsBothRoles() throws Exception {
        String planId = createPlan();
        Instant now = Instant.now();
        Person existing = personRepository.insert(new Person(
                Uuid7.generate(), "Kalle", "Karlsson", null, null, null, null, true, false, null, now, now));
        org.assertj.core.api.Assertions.assertThat(existing.canBeCoach()).isFalse();

        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                existing.id(), null, null, null, null, 500.0, null, null, null, null, true, null));
        mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personId").value(existing.id()))
                .andExpect(jsonPath("$.canAlsoTrainAsParticipant").value(true));

        Person updated = personRepository.findById(existing.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.canBeCoach()).isTrue();
        org.assertj.core.api.Assertions.assertThat(updated.canBeParticipant()).isTrue(); // untouched
    }

    @Test
    void levelOutOfRangeIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, 1500.0, null, null, null, null, false, null));

        mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void minLevelAboveMaxLevelIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, 800.0, 400.0, null, null, false, null));

        mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** M5 review test gap: maxGroupsPerDay > maxGroupsPerWeek is contradictory and must be a 400. */
    @Test
    void maxGroupsPerDayGreaterThanPerWeekIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, null, null, 3, 2, false, null));

        mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("maxGroupsPerDay must be <= maxGroupsPerWeek")));

        // Same rule against the MERGED values on PATCH: create a valid coach (day 2 / week 4),
        // then patch only maxGroupsPerWeek below the existing maxGroupsPerDay.
        String validBody = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, null, null, 2, 4, false, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/coaches/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxGroupsPerWeek\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchUpdatesFieldsAndSupportsExplicitNullClearing() throws Exception {
        String planId = createPlan();
        String createBody = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, 500.0, 300.0, 700.0, 2, 4, false, "kommentar"));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/coaches/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coachLevel\": 620, \"notes\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coachLevel").value(620.0))
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.canCoachMinLevel").value(300.0)); // untouched

        mockMvc.perform(delete("/api/coaches/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/coaches/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateCoachForSamePersonAndPlanReturns409() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, null, null, null, null, false, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String personId = objectMapper.readTree(response).get("personId").asText();

        String secondBody = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                personId, null, null, null, null, null, null, null, null, null, false, null));
        mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict());
    }

    @Test
    void availabilityTriStateRoundTrip() throws Exception {
        String planId = createPlan();
        String slotA = createSlot(planId, "16:30", "18:00");
        String slotB = createSlot(planId, "18:00", "19:30");
        String slotC = createSlot(planId, "19:30", "21:00");

        String coachBody = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, null, null, null, null, false, null));
        String coachResponse = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(coachBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String coachId = objectMapper.readTree(coachResponse).get("id").asText();

        // Initially unlisted everywhere.
        mockMvc.perform(get("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        List<CoachController.AvailabilityEntry> entries = List.of(
                new CoachController.AvailabilityEntry(slotA, "AVAILABLE"),
                new CoachController.AvailabilityEntry(slotB, "PREFERRED"),
                new CoachController.AvailabilityEntry(slotC, "UNAVAILABLE"));
        mockMvc.perform(put("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(entries)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Replacing with a smaller set drops the omitted slot back to "unlisted".
        List<CoachController.AvailabilityEntry> replacement = List.of(
                new CoachController.AvailabilityEntry(slotA, "UNAVAILABLE"));
        mockMvc.perform(put("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("UNAVAILABLE"));

        mockMvc.perform(get("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void availabilityRejectsInvalidKindAndForeignTimeSlot() throws Exception {
        String planId = createPlan();
        String otherPlanId = createPlan();
        String otherSlot = createSlot(otherPlanId, "18:00", "19:30");

        String coachBody = objectMapper.writeValueAsString(new CoachController.CreateCoachRequest(
                null, "Anna", "Andersson", null, null, null, null, null, null, null, false, null));
        String coachResponse = mockMvc.perform(post("/api/plans/" + planId + "/coaches")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(coachBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String coachId = objectMapper.readTree(coachResponse).get("id").asText();

        mockMvc.perform(put("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"timeSlotId\": \"" + otherSlot + "\", \"kind\": \"AVAILABLE\"}]"))
                .andExpect(status().isBadRequest());

        String ownSlot = createSlot(planId, "18:00", "19:30");
        mockMvc.perform(put("/api/plans/" + planId + "/coaches/" + coachId + "/availability")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"timeSlotId\": \"" + ownSlot + "\", \"kind\": \"MAYBE\"}]"))
                .andExpect(status().isBadRequest());
    }
}
