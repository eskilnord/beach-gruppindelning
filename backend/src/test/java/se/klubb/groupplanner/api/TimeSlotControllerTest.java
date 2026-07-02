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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MockMvc CRUD + validation tests for {@code /api/plans/{planId}/time-slots} and
 * {@code /api/time-slots/{id}} (spec §6.7/§7.9/§12.1, docs/plan.md M5 row).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimeSlotControllerTest {

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

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, null, now, now));
        return plan.id();
    }

    @Test
    void createReadUpdateDeleteHappyPathWithAutoLabel() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:00", "19:30", null));

        String response = mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dayOfWeek").value("THURSDAY"))
                .andExpect(jsonPath("$.startTime").value("18:00"))
                .andExpect(jsonPath("$.endTime").value("19:30"))
                .andExpect(jsonPath("$.durationMinutes").value(90))
                // spec §12.1 example label, with the en dash the spec text itself uses.
                .andExpect(jsonPath("$.label").value("Torsdag 18.00–19.30"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/plans/" + planId + "/time-slots").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(get("/api/time-slots/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Torsdag 18.00–19.30"));

        mockMvc.perform(patch("/api/time-slots/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endTime\": \"20:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endTime").value("20:00"))
                .andExpect(jsonPath("$.durationMinutes").value(120))
                // Auto-label regenerates since it wasn't overridden and startTime/endTime changed.
                .andExpect(jsonPath("$.label").value("Torsdag 18.00–20.00"));

        mockMvc.perform(delete("/api/time-slots/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/time-slots/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void explicitLabelIsPreservedAcrossPatchOfUnrelatedField() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:00", "19:30", "Kvällspass"));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Kvällspass"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        // PATCH omits "label" entirely -> the custom label must survive.
        mockMvc.perform(patch("/api/time-slots/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfWeek\": \"FRIDAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Kvällspass"))
                .andExpect(jsonPath("$.dayOfWeek").value("FRIDAY"));

        // Explicit null clears it back to auto-generation.
        mockMvc.perform(patch("/api/time-slots/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Fredag 18.00–19.30"));
    }

    @Test
    void rejectsInvalidTimeFormat() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18.00", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsStartNotBeforeEnd() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "19:30", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsMissingDayOfWeekAndDate() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest(null, null, "18:00", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsInvalidDayOfWeekName() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("TORSDAG", null, "18:00", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void exactDuplicateReturns409ButOverlappingDifferentTimesAreAllowed() throws Exception {
        String planId = createPlan();
        String body1 = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:00", "19:30", null));
        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isCreated());

        // Exact duplicate (same day + start + end) -> 409.
        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());

        // A different (but overlapping, e.g. same start time on a different day, or an adjacent
        // slot) time is allowed - overlap between slots for different courts is fine (spec §12.1/
        // docs/plan.md M5 row: "Overlap between slots is ALLOWED").
        String body2 = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:30", "20:00", null));
        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isCreated());
    }

    @Test
    void dateBasedSlotDerivesSwedishDayNameFromDate() throws Exception {
        String planId = createPlan();
        // 2026-07-02 is a Thursday.
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest(null, "2026-07-02", "18:00", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value("2026-07-02"))
                .andExpect(jsonPath("$.label").value("Torsdag 18.00–19.30"));
    }

    /** M5 review fix: both dayOfWeek AND date set is ambiguous and must be a clear 400. */
    @Test
    void rejectsBothDayOfWeekAndDateSet() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", "2026-07-02", "18:00", "19:30", null));

        mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("dayOfWeek and date cannot both be set")));

        // Same rule on PATCH: adding a date to a dayOfWeek slot without clearing dayOfWeek -> 400;
        // clearing dayOfWeek in the same request is the accepted way to convert the slot.
        String createBody = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:00", "19:30", null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/time-slots/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"2026-07-02\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/time-slots/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"2026-07-02\", \"dayOfWeek\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-07-02"))
                .andExpect(jsonPath("$.dayOfWeek").doesNotExist());
    }

    /**
     * M5 review fix: day_of_week stores enum NAMES whose lexicographic order (FRIDAY &lt; THURSDAY
     * &lt; TUESDAY) is not weekday order — the list (and therefore the grouped-blocks view and the
     * capacity breakdown, which read through the same repository method) must sort by weekday
     * ordinal, then start time.
     */
    @Test
    void listIsOrderedByWeekdayOrdinalThenStartTimeNotAlphabetically() throws Exception {
        String planId = createPlan();
        for (String[] slot : new String[][] {
                {"THURSDAY", "18:00", "19:30"},
                {"TUESDAY", "18:00", "19:30"},
                {"FRIDAY", "08:00", "09:30"},
                {"TUESDAY", "16:30", "18:00"}}) {
            String body = objectMapper.writeValueAsString(
                    new TimeSlotController.CreateTimeSlotRequest(slot[0], null, slot[1], slot[2], null));
            mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                            .header("X-GP-Token", VALID_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/plans/" + planId + "/time-slots").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayOfWeek").value("TUESDAY"))
                .andExpect(jsonPath("$[0].startTime").value("16:30"))
                .andExpect(jsonPath("$[1].dayOfWeek").value("TUESDAY"))
                .andExpect(jsonPath("$[1].startTime").value("18:00"))
                .andExpect(jsonPath("$[2].dayOfWeek").value("THURSDAY"))
                .andExpect(jsonPath("$[3].dayOfWeek").value("FRIDAY"));
    }

    @Test
    void createUnderUnknownPlanReturns404() throws Exception {
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest("THURSDAY", null, "18:00", "19:30", null));

        mockMvc.perform(post("/api/plans/does-not-exist/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listWithoutTokenReturns401() throws Exception {
        String planId = createPlan();
        mockMvc.perform(get("/api/plans/" + planId + "/time-slots"))
                .andExpect(status().isUnauthorized());
    }
}
