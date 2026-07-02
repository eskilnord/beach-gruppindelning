package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MockMvc tests for the block-generation core of M5 (spec §12.2/§12.3): {@code PUT
 * /api/plans/{planId}/time-slots/{slotId}/courts}, manual {@code PATCH
 * /api/training-blocks/{id}}, and the grouped {@code GET /api/plans/{planId}/training-blocks} view.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrainingBlockControllerTest {

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

    private String createSlot(String planId, String dayOfWeek, String start, String end) throws Exception {
        String body = objectMapper.writeValueAsString(
                new TimeSlotController.CreateTimeSlotRequest(dayOfWeek, null, start, end, null));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/time-slots")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    /** Spec §12.2's own worked example: "Torsdag 18.00–19.30: 4 banor" → 4 blocks named Bana 1..4. */
    @Test
    void specExampleFourCourtsCreatesFourBlocksNamedBana1To4() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        String response = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].courtName").value("Bana 1"))
                .andExpect(jsonPath("$[1].courtName").value("Bana 2"))
                .andExpect(jsonPath("$[2].courtName").value("Bana 3"))
                .andExpect(jsonPath("$[3].courtName").value("Bana 4"))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode block : objectMapper.readTree(response)) {
            assertThat(block.get("active").asBoolean()).isTrue();
            assertThat(block.get("timeSlotId").asText()).isEqualTo(slotId);
        }
    }

    @Test
    void sameCountTwiceIsIdempotentNoChangeInBlockIds() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        String firstResponse = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 3}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> firstIds = idsOf(firstResponse);

        String secondResponse = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 3}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> secondIds = idsOf(secondResponse);

        assertThat(secondIds).containsExactlyElementsOf(firstIds);
    }

    @Test
    void shrinkingThenGrowingBackPreservesBlockIdentity() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        String grownResponse = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> originalIds = idsOf(grownResponse);
        assertThat(originalIds).hasSize(4);

        // Shrink to 2 -> blocks 3 and 4 deactivate (never deleted).
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4)) // rows still exist, GET-after-shrink returns all 4
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(true))
                .andExpect(jsonPath("$[2].active").value(false))
                .andExpect(jsonPath("$[3].active").value(false));

        // Grow back to 4 -> the exact same 4 block ids reappear, all active again.
        String regrownResponse = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> regrownIds = idsOf(regrownResponse);
        assertThat(regrownIds).containsExactlyElementsOf(originalIds);
        for (JsonNode block : objectMapper.readTree(regrownResponse)) {
            assertThat(block.get("active").asBoolean()).isTrue();
        }
    }

    @Test
    void deactivateBeyondNLeavesBlocksBelowNUntouched() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 5}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(true))
                .andExpect(jsonPath("$[2].active").value(true))
                .andExpect(jsonPath("$[3].active").value(false))
                .andExpect(jsonPath("$[4].active").value(false));
    }

    @Test
    void manualPatchDeactivatesOneBlockWithoutAffectingSiblings() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "21:00", "22:30");

        String response = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode blocks = objectMapper.readTree(response);
        String bana4Id = null;
        for (JsonNode block : blocks) {
            if ("Bana 4".equals(block.get("courtName").asText())) {
                bana4Id = block.get("id").asText();
            }
        }
        assertThat(bana4Id).isNotNull();

        // Spec §12.3: "Bana 4 är inte tillgänglig 21.00–22.30."
        mockMvc.perform(patch("/api/training-blocks/" + bana4Id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.courtName").value("Bana 4"));

        String grouped = mockMvc.perform(get("/api/plans/" + planId + "/training-blocks").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode slotGroup = objectMapper.readTree(grouped).get(0);
        assertThat(slotGroup.get("blocks")).hasSize(4);
        int activeCount = 0;
        for (JsonNode block : slotGroup.get("blocks")) {
            if (block.get("active").asBoolean()) {
                activeCount++;
            }
        }
        assertThat(activeCount).isEqualTo(3);
    }

    /** Multiple slots share the club-wide "Bana N" court pool (docs/plan.md M5 row E2E scenario). */
    @Test
    void threeSlotsWithFourFourThreeCoursesShareClubWideCourtsAndTotalElevenBlocks() throws Exception {
        String planId = createPlan();
        String slot1 = createSlot(planId, "THURSDAY", "16:30", "18:00");
        String slot2 = createSlot(planId, "THURSDAY", "18:00", "19:30");
        String slot3 = createSlot(planId, "THURSDAY", "19:30", "21:00");

        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slot1 + "/courts")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"count\": 4}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slot2 + "/courts")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"count\": 4}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slot3 + "/courts")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"count\": 3}"))
                .andExpect(status().isOk());

        // Club-wide court pool tops out at "Bana 4" - reused (not duplicated) across all 3 slots.
        // (Other test methods in this class may have already created their own "Bana N" courts
        // under the same shared default venue, so this asserts containment, not an exact count.)
        String courtsResponse = mockMvc.perform(get("/api/courts").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> courtNames = new ArrayList<>();
        for (JsonNode court : objectMapper.readTree(courtsResponse)) {
            courtNames.add(court.get("name").asText());
        }
        assertThat(courtNames).contains("Bana 1", "Bana 2", "Bana 3", "Bana 4");

        String grouped = mockMvc.perform(get("/api/plans/" + planId + "/training-blocks").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int totalActive = 0;
        for (JsonNode slotGroup : objectMapper.readTree(grouped)) {
            for (JsonNode block : slotGroup.get("blocks")) {
                if (block.get("active").asBoolean()) {
                    totalActive++;
                }
            }
        }
        assertThat(totalActive).isEqualTo(11);
    }

    @Test
    void negativeCountIsRejected() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": -1}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * M5 review fix (deactivation_source): a manual §12.3 deactivation must SURVIVE re-declaring
     * the same court count — regeneration only auto-reactivates blocks it deactivated itself
     * (SHRINK), never the user's explicit exceptions (MANUAL). PATCH {active:true} clears the
     * marker, after which generation manages the block again.
     */
    @Test
    void manualDeactivationSurvivesRegenerationUntilManuallyReactivated() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "18:00", "19:30");

        String response = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bana2Id = blockIdByCourtName(response, "Bana 2");

        // Manual §12.3 exception on Bana 2.
        mockMvc.perform(patch("/api/training-blocks/" + bana2Id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Re-declare the SAME count: Bana 2 must STAY inactive (the review's regression case).
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true))   // Bana 1
                .andExpect(jsonPath("$[1].active").value(false))  // Bana 2 - manual exception preserved
                .andExpect(jsonPath("$[2].active").value(true))   // Bana 3
                .andExpect(jsonPath("$[3].active").value(true));  // Bana 4

        // Shrink to 1 (deactivates Bana 3+4 as SHRINK), then grow back to 4: the SHRINK blocks
        // reactivate, the MANUAL one still does not.
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(false))  // MANUAL still wins
                .andExpect(jsonPath("$[2].active").value(true))   // SHRINK -> reactivated
                .andExpect(jsonPath("$[3].active").value(true));  // SHRINK -> reactivated

        // Manual reactivation clears the marker...
        mockMvc.perform(patch("/api/training-blocks/" + bana2Id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // ...so from here generation manages Bana 2 normally again (shrink+grow round-trip works).
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(true))
                .andExpect(jsonPath("$[2].active").value(true))
                .andExpect(jsonPath("$[3].active").value(true));
    }

    /**
     * M5 review test gap: deleting a court that training blocks reference. V1 declares
     * {@code training_block.court_id REFERENCES court(id) ON DELETE RESTRICT} (verified in
     * V1__core.sql), so SQLite raises SQLITE_CONSTRAINT and the API surfaces the standard 409.
     */
    @Test
    void deletingACourtReferencedByTrainingBlocksReturns409() throws Exception {
        String planId = createPlan();
        String slotId = createSlot(planId, "THURSDAY", "07:00", "08:30");

        String response = mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": 1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String courtId = objectMapper.readTree(response).get(0).get("courtId").asText();

        mockMvc.perform(delete("/api/courts/" + courtId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());

        // The court (and its block) are untouched by the failed delete.
        mockMvc.perform(get("/api/courts/" + courtId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());
    }

    private String blockIdByCourtName(String jsonArray, String courtName) throws Exception {
        for (JsonNode block : objectMapper.readTree(jsonArray)) {
            if (courtName.equals(block.get("courtName").asText())) {
                return block.get("id").asText();
            }
        }
        throw new AssertionError("No block for court " + courtName + " in: " + jsonArray);
    }

    private List<String> idsOf(String jsonArray) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(jsonArray)) {
            ids.add(node.get("id").asText());
        }
        return ids;
    }
}
