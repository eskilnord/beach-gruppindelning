package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.Venue;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code GET /api/plans/{planId}/groups/sync-status} (WI-C, "re-run doesn't feel like it re-runs"
 * user feedback v0.4 #4, root cause A) — MockMvc end-to-end, mirroring {@code
 * GroupGeneratorTest}'s own plan/participant/block fixture helpers rather than reusing its private
 * ones (kept independent so this test can freely PATCH the plan mid-test via the real HTTP
 * endpoint, exercising {@code GroupGenerator#checkSyncStatus}'s DRY-RUN through the exact same
 * request path a real client would use).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupSyncStatusIntegrationTest {

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
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private CourtRepository courtRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;

    private String createPlan(Integer target, Integer min, Integer max) {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "Herr", "draft", target, min, max, null, now, now));
        return plan.id();
    }

    private void addParticipants(String planId, int count, double startLevel) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), "P" + i, "Testsson", null, null, null, null, true, false, null, now, now));
            participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId, null, null, null, null, startLevel + i, 1.0, null, null, null, false, false));
        }
    }

    private void addActiveBlocks(String planId, int count) {
        Venue venue = venueRepository.insert(new Venue(Uuid7.generate(), "Hallen-" + Uuid7.generate().substring(0, 8), null));
        for (int i = 0; i < count; i++) {
            Court court = courtRepository.insert(new Court(Uuid7.generate(), venue.id(), "Bana " + i, true, null));
            TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                    Uuid7.generate(), planId, "THURSDAY", null, "18:0" + i, "19:3" + i, null, "Torsdag " + i));
            trainingBlockRepository.insert(new TrainingBlock(Uuid7.generate(), slot.id(), court.id(), planId, true, false, null));
        }
    }

    private void generateGroups(String planId) throws Exception {
        mockMvc.perform(post("/api/plans/" + planId + "/groups/generate").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void freshlyGeneratedGroupsAreNotStale() throws Exception {
        String planId = createPlan(5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2); // ceil(10/5)=2, clamp(2,1,2)=2
        generateGroups(planId);

        mockMvc.perform(get("/api/plans/" + planId + "/groups/sync-status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.reasons").isEmpty());
    }

    @Test
    void patchingTargetSizeMakesStatusStaleWithASizeReason() throws Exception {
        String planId = createPlan(5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);
        generateGroups(planId);

        // target 5 -> 6 with min/max left at their explicit 4/6: ceil(10/6)=2, clamp(2,1,2)=2 - the
        // GROUP COUNT stays the same, isolating this to a pure size-defaults mismatch (no count
        // reason should fire alongside it).
        mockMvc.perform(patch("/api/plans/" + planId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultGroupTargetSize\": 6}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/plans/" + planId + "/groups/sync-status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.containsString("mål 6")));
    }

    @Test
    void addingParticipantsBeyondGeneratedCapacityMakesStatusStaleWithACountReason() throws Exception {
        String planId = createPlan(5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 3); // ceil(10/5)=2, clamp(2,1,3)=2 - generated groupCount is 2.
        generateGroups(planId);

        addParticipants(planId, 6, 400); // now 16 active: ceil(16/5)=4, clamp(4,1,3)=3 - expects 3.

        mockMvc.perform(get("/api/plans/" + planId + "/groups/sync-status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.containsString("3 grupper")))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.containsString("2 är genererade")));
    }

    @Test
    void noGroupsYetWithParticipantsIsStale() throws Exception {
        String planId = createPlan(5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);
        // Deliberately never calls generateGroups - the prerequisite step was simply never run.

        mockMvc.perform(get("/api/plans/" + planId + "/groups/sync-status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.reasons[0]").value("Inga grupper är genererade ännu."));
    }

    @Test
    void noGroupsAndNoParticipantsIsNotStale() throws Exception {
        String planId = createPlan(5, 4, 6);
        addActiveBlocks(planId, 2);

        mockMvc.perform(get("/api/plans/" + planId + "/groups/sync-status").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.reasons").isEmpty());
    }
}
