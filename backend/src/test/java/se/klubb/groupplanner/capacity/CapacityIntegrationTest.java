package se.klubb.groupplanner.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import se.klubb.groupplanner.api.TimeSlotController;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * End-to-end capacity scenario (docs/plan.md M5 row item 5): bulk-load the committed anonymized
 * {@code large-120} dataset (actually 130 participants — see test-data/datasets/large-120/README.md)
 * via {@link ParticipantProfileRepository} (never the confidential real file, CLAUDE.md), create 4
 * time slots × 3 courts (= 12 active TrainingBlocks) through the real {@code TimeSlotController}/
 * {@code TrainingBlockController} endpoints, and assert the capacity endpoint reports
 * {@code OVER_TARGET} (12 × target 10 = 120 &lt; 130, but 12 × max 12 = 144 &ge; 130).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CapacityIntegrationTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final Path PARTICIPANTS_CSV = Path.of("..", "test-data", "datasets", "large-120", "participants.csv");

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
    private CapacityService capacityService;

    @Test
    void oneHundredThirtyParticipantsAgainstFourSlotsByThreeCoursesShowsOverTarget() throws Exception {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), season.id(), "Torsdag Herr", "beach", "draft", 10, 8, 12, null, now, now));
        String planId = plan.id();

        int importedCount = bulkLoadParticipants(planId);
        assertThat(importedCount).isEqualTo(130);

        List<String> slotIds = List.of(
                createSlot(planId, "16:30", "18:00"),
                createSlot(planId, "18:00", "19:30"),
                createSlot(planId, "19:30", "21:00"),
                createSlot(planId, "21:00", "22:30"));
        for (String slotId : slotIds) {
            mockMvc.perform(put("/api/plans/" + planId + "/time-slots/" + slotId + "/courts")
                            .header("X-GP-Token", VALID_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"count\": 3}"))
                    .andExpect(status().isOk());
        }

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.participantCount()).isEqualTo(130);
        assertThat(response.activeTrainingBlockCount()).isEqualTo(12);
        assertThat(response.targetCapacity()).isEqualTo(120);
        assertThat(response.maxCapacity()).isEqualTo(144);
        assertThat(response.waitlistRisk()).isEqualTo(CapacityResponse.RISK_OVER_TARGET);
        assertThat(response.waitlistMessage()).isEqualTo("Möjligt, men grupperna blir större än target");
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

    /** Reads the committed anonymized CSV directly (name/ranking columns only - no xlsx parsing needed for this scenario). */
    private int bulkLoadParticipants(String planId) throws Exception {
        List<String> lines = Files.readAllLines(PARTICIPANTS_CSV, StandardCharsets.UTF_8);
        Instant now = Instant.now();
        int count = 0;
        for (int i = 1; i < lines.size(); i++) { // skip header
            String[] cols = lines.get(i).split(",", -1);
            String firstName = cols[1];
            String lastName = cols[2];
            String rankingPoints = cols[4];
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), firstName, lastName, null, null, null, null, true, false, null, now, now));
            participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId,
                    rankingPoints.isBlank() ? null : Double.parseDouble(rankingPoints), "seriespel",
                    null, null, null, null, null, null, null, false, false));
            count++;
        }
        return count;
    }
}
