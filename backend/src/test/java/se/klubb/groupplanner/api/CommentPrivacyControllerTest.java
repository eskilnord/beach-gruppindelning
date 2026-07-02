package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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

/**
 * Comment delete/anonymize actions (spec §21.2, docs/plan.md "Privacy corrections"). Extends the
 * comment-leak test surface (CLAUDE.md: "There is an automated leak test - keep it green") by
 * asserting the DB columns are actually {@code NULL} afterwards, at the raw SQL level (not just via
 * the domain-record mapping, which could mask a query bug).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentPrivacyControllerTest {

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
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, now, now));
        return plan.id();
    }

    private String createParticipantWithComments(String planId, String comment, String note) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Kalle", "Karlsson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, null, null, comment, note, false, false));
        return profile.id();
    }

    private String commentColumnsFor(String participantId) {
        return jdbcClient.sql("SELECT imported_comment || '|' || internal_note AS c FROM participant_profile WHERE id = :id "
                        + "AND (imported_comment IS NOT NULL OR internal_note IS NOT NULL)")
                .param("id", participantId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void deleteOneParticipantsCommentsNullsBothColumnsInTheDatabase() throws Exception {
        String planId = createPlan();
        String pid = createParticipantWithComments(planId, "Känslig kommentar", "Intern anteckning");

        mockMvc.perform(delete("/api/plans/" + planId + "/participants/" + pid + "/comments")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());

        assertThat(commentColumnsFor(pid)).isNull();

        // The API's own read view agrees.
        mockMvc.perform(get("/api/participants/" + pid).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedComment").doesNotExist())
                .andExpect(jsonPath("$.internalNote").doesNotExist());
    }

    @Test
    void anonymizeRequiresExplicitConfirmTrue() throws Exception {
        String planId = createPlan();
        createParticipantWithComments(planId, "Hemligt", null);

        mockMvc.perform(post("/api/plans/" + planId + "/comments/anonymize")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/api/plans/" + planId + "/comments/anonymize")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void anonymizeNullsCommentsForEveryParticipantInThePlanButNotOtherPlans() throws Exception {
        String planId = createPlan();
        String otherPlanId = createPlan();
        String p1 = createParticipantWithComments(planId, "Kommentar 1", "Anteckning 1");
        String p2 = createParticipantWithComments(planId, null, "Bara intern anteckning");
        String p3 = createParticipantWithComments(planId, null, null);
        String otherPlanParticipant = createParticipantWithComments(otherPlanId, "Ska inte påverkas", "Ej heller detta");

        String response = mockMvc.perform(post("/api/plans/" + planId + "/comments/anonymize")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\": true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("\"clearedCount\":2");

        // Leak-test extension (CLAUDE.md): assert at the raw DB level, both participants with
        // comments in this plan are now NULL in both sensitive columns.
        assertThat(commentColumnsFor(p1)).isNull();
        assertThat(commentColumnsFor(p2)).isNull();
        assertThat(commentColumnsFor(p3)).isNull(); // already had none - stays none.

        // A different plan's comments are untouched.
        assertThat(commentColumnsFor(otherPlanParticipant)).isEqualTo("Ska inte påverkas|Ej heller detta");
    }

    @Test
    void deleteCommentsForUnknownParticipantReturns404() throws Exception {
        String planId = createPlan();
        mockMvc.perform(delete("/api/plans/" + planId + "/participants/does-not-exist/comments")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void anonymizeForUnknownPlanReturns404() throws Exception {
        mockMvc.perform(post("/api/plans/does-not-exist/comments/anonymize")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
