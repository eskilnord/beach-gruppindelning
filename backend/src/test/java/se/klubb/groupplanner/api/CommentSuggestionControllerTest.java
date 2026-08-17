package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/** 404 guards + response shape for the WP2 "Tolkningsförslag" read endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class CommentSuggestionControllerTest {

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

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26-" + Uuid7.generate(), null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private String createParticipant(String planId, String comment) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Anna", "Svensson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, null, null, comment, null, false, false, false));
        return profile.id();
    }

    @Test
    void participantEndpointReturnsShapeWithEmptySuggestionsForNoComment() throws Exception {
        String planId = createPlan();
        String pid = createParticipant(planId, null);
        mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/comment-suggestions")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantId").value(pid))
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions").isEmpty());
    }

    @Test
    void planEndpointReturnsListShape() throws Exception {
        String planId = createPlan();
        createParticipant(planId, null);
        mockMvc.perform(get("/api/plans/" + planId + "/comment-suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    /** Review fix MAJOR 6 ("comment minimization"): the plan-wide endpoint returns COUNTS ONLY -
     *  never `matchedText`, never candidate `targets` - even though the underlying comment obviously
     *  contains a name. */
    @Test
    void planEndpointReturnsCountsOnlyNeverMatchedTextOrTargets() throws Exception {
        String planId = createPlan();
        createParticipant(planId, null); // "Anna Svensson" - the resolvable target for the comment below.
        Instant now = Instant.now();
        Person source = personRepository.insert(new Person(
                Uuid7.generate(), "Bertil", "Karlsson", null, null, null, null, true, false, null, now, now));
        participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), source.id(), planId, null, null, null, null, null, null, null,
                "Vill gärna spela med Anna Svensson.", null, false, false, false));

        String body = mockMvc.perform(get("/api/plans/" + planId + "/comment-suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].participantId").exists())
                .andExpect(jsonPath("$[0].suggestionCount").value(1))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("matchedText").doesNotContain("targets");
    }

    @Test
    void participantEndpointReturns404ForUnknownPlan() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/participants/does-not-exist/comment-suggestions")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void participantEndpointReturns404ForParticipantNotInPlan() throws Exception {
        String planId = createPlan();
        String otherPlanId = createPlan();
        String pid = createParticipant(otherPlanId, null);
        mockMvc.perform(get("/api/plans/" + planId + "/participants/" + pid + "/comment-suggestions")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void planEndpointReturns404ForUnknownPlan() throws Exception {
        mockMvc.perform(get("/api/plans/does-not-exist/comment-suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
