package se.klubb.groupplanner.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.suggest.CommentSuggestion.Confidence;
import se.klubb.groupplanner.suggest.CommentSuggestion.ParticipantSuggestions;
import se.klubb.groupplanner.util.Uuid7;

/** Integration coverage over a real seeded plan (spec's standard field keys from V2), plus the
 *  privacy persistence check the WP2 brief calls for. */
@SpringBootTest
@AutoConfigureMockMvc
class CommentSuggestionServiceTest {

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
    private CommentSuggestionService commentSuggestionService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26-" + Uuid7.generate(), null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private String createParticipant(String planId, String firstName, String lastName, String comment) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, lastName, null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, null, null, comment, null, false, false, false));
        return profile.id();
    }

    private String createCoach(String planId, String firstName, String lastName) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, lastName, null, null, null, null, false, true, null, now, now));
        CoachProfile coach = coachProfileRepository.insert(new CoachProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, false, null, false));
        return coach.id();
    }

    private String createTimeSlot(String planId, String dayOfWeek, String start, String end) {
        TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                Uuid7.generate(), planId, dayOfWeek, null, start, end, null, dayOfWeek + " " + start));
        return slot.id();
    }

    @Test
    void emptyForNoComment() {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna", "Svensson", null);
        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void playWithSuggestionResolvesRosterTarget() {
        String planId = createPlan();
        String target = createParticipant(planId, "Anna", "Svensson", null);
        String source = createParticipant(planId, "Bertil", "Karlsson", "Vill gärna spela med Anna Svensson.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, source);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.kind()).isEqualTo(SuggestionKind.PLAY_WITH);
        assertThat(suggestion.fieldKey()).isEqualTo("playWith");
        assertThat(suggestion.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(suggestion.targets()).hasSize(1);
        assertThat(suggestion.targets().get(0).id()).isEqualTo(target);
        assertThat(suggestion.alreadyApplied()).isFalse();
    }

    @Test
    void alreadyAppliedTrueAfterFieldValuePut() throws Exception {
        String planId = createPlan();
        String target = createParticipant(planId, "Anna", "Svensson", null);
        String source = createParticipant(planId, "Bertil", "Karlsson", "Vill gärna spela med Anna Svensson.");

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + source + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playWith\": [\"" + target + "\"]}"))
                .andExpect(status().isOk());

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, source);
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).alreadyApplied()).isTrue();
    }

    @Test
    void timeCannotResolvesRealPlanSlotIds() {
        String planId = createPlan();
        String slot1 = createTimeSlot(planId, "THURSDAY", "18:00", "19:30");
        String slot2 = createTimeSlot(planId, "THURSDAY", "19:30", "21:00");
        createTimeSlot(planId, "TUESDAY", "18:00", "19:30");
        String pid = createParticipant(planId, "Anna", "Svensson", "Kan inte torsdagar.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.kind()).isEqualTo(SuggestionKind.TIME_CANNOT);
        assertThat(suggestion.timeSlotIds()).containsExactlyInAnyOrder(slot1, slot2);
    }

    /** Review fix MAJOR 3: a bare number with no minutes, no "kl" prefix, and no governing
     *  before/after preposition must never be read as a clock hour - "18" here is a date ("18
     *  augusti"), not a time. */
    @Test
    void bareNumberNotGovernedByAnyTimeContextIsIgnoredEntirely() {
        String planId = createPlan();
        createTimeSlot(planId, "THURSDAY", "18:00", "19:30");
        String pid = createParticipant(planId, "Anna", "Svensson", "Bortrest till 18 augusti, annars inga problem.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).isEmpty();
    }

    /** Review fix MAJOR 4: "efter 19" must resolve only the slot whose START is at/after 19:00, not
     *  the earlier 18:00 slot - and the bare "19" here IS accepted (unlike the MAJOR 3 case above)
     *  because it is directly governed by "efter". */
    @Test
    void efterNineteenResolvesOnlyTheLaterSlot() {
        String planId = createPlan();
        String earlySlot = createTimeSlot(planId, "THURSDAY", "18:00", "19:30");
        String lateSlot = createTimeSlot(planId, "THURSDAY", "19:30", "21:00");
        String pid = createParticipant(planId, "Anna", "Svensson", "Kan inte torsdagar efter 19.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.timeSlotIds()).containsExactly(lateSlot);
        assertThat(suggestion.timeSlotIds()).doesNotContain(earlySlot);
    }

    /** Review fix MAJOR 4: a plain (direction-less) explicit time must resolve the slot whose
     *  [start, end) interval CONTAINS it, not require an exact start-time match. */
    @Test
    void plainDecimalTimeResolvesTheSlotThatContainsIt() {
        String planId = createPlan();
        String slot = createTimeSlot(planId, "THURSDAY", "18:00", "19:30");
        createTimeSlot(planId, "THURSDAY", "19:30", "21:00");
        String pid = createParticipant(planId, "Anna", "Svensson", "Kan inte torsdagar 18.30.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).timeSlotIds()).containsExactly(slot);
    }

    /** Review fix MAJOR 4: "före 19" must resolve only the slot whose START is strictly before
     *  19:00. */
    @Test
    void foreNineteenResolvesOnlyTheEarlierSlot() {
        String planId = createPlan();
        String earlySlot = createTimeSlot(planId, "THURSDAY", "18:00", "19:30");
        String lateSlot = createTimeSlot(planId, "THURSDAY", "19:30", "21:00");
        String pid = createParticipant(planId, "Anna", "Svensson", "Föredrar torsdagar före 19.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.timeSlotIds()).containsExactly(earlySlot);
        assertThat(suggestion.timeSlotIds()).doesNotContain(lateSlot);
    }

    /** Review fix (minor 1): a single unrelated pre-existing entry in the field's current array must
     *  never block an UNCERTAIN suggestion's DIFFERENT candidate from being reported as applicable -
     *  the per-candidate `applied` flag, not a suggestion-wide "any present" check. */
    @Test
    void perCandidateAppliedNeverBlocksADifferentUnappliedCandidate() throws Exception {
        String planId = createPlan();
        String annaA = createParticipant(planId, "Anna", "Andersson", null);
        String annaB = createParticipant(planId, "Anna", "Bengtsson", null);
        String source = createParticipant(planId, "Cecilia", "Karlsson", "Vill gärna spela med Anna.");

        // Pre-apply ONE of the two ambiguous "Anna" candidates.
        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + source + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playWith\": [\"" + annaA + "\"]}"))
                .andExpect(status().isOk());

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, source);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.alreadyApplied()).isFalse(); // annaB is still unapplied.
        assertThat(suggestion.targets()).hasSize(2);
        assertThat(suggestion.targets().stream().filter(t -> t.id().equals(annaA)).findFirst().orElseThrow().applied()).isTrue();
        assertThat(suggestion.targets().stream().filter(t -> t.id().equals(annaB)).findFirst().orElseThrow().applied()).isFalse();
    }

    @Test
    void coachWishResolvesAgainstCoachRosterOnly() {
        String planId = createPlan();
        createParticipant(planId, "Vera", "Nilsson", null); // same first name as coach - must not match
        String coachId = createCoach(planId, "Vera", "Nilsson");
        String pid = createParticipant(planId, "Anna", "Svensson", "Vill ha Vera Nilsson som tränare.");

        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        CommentSuggestion suggestion = result.suggestions().get(0);
        assertThat(suggestion.kind()).isEqualTo(SuggestionKind.COACH_WISH);
        assertThat(suggestion.targets().get(0).id()).isEqualTo(coachId);
    }

    @Test
    void injuryNoteAndLevelChangeAreFlagKindsWithNoFieldKey() {
        String planId = createPlan();
        String pid = createParticipant(planId, "Anna", "Svensson", "Har ont i axeln.");
        ParticipantSuggestions result = commentSuggestionService.suggestionsForParticipant(planId, pid);
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).kind()).isEqualTo(SuggestionKind.INJURY_NOTE);
        assertThat(result.suggestions().get(0).fieldKey()).isNull();
        assertThat(result.suggestions().get(0).targets()).isEmpty();
    }

    @Test
    void planLevelAggregatesOnlyParticipantsWithSuggestions() {
        String planId = createPlan();
        String target = createParticipant(planId, "Anna", "Svensson", null);
        createParticipant(planId, "NoComment", "Person", null);
        createParticipant(planId, "Bertil", "Karlsson", "Vill gärna spela med Anna Svensson.");
        createParticipant(planId, "Cecilia", "Doe", "Ser fram emot terminen!");

        List<ParticipantSuggestions> all = commentSuggestionService.suggestionsForPlan(planId);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).suggestions()).hasSize(1);
        assertThat(all.get(0).suggestions().get(0).targets().get(0).id()).isEqualTo(target);
    }

    /** Review fix MAJOR 6 ("comment minimization"): the plan-wide counts projection carries only
     *  {@code participantId}/{@code suggestionCount} - no {@code matchedText}, no candidate names -
     *  and counts only NOT-YET-applied suggestions, omitting a participant once everything is
     *  applied (same "only participants with something outstanding" contract as the full-detail
     *  method). */
    @Test
    void suggestionCountsForPlanCountsOnlyUnappliedAndOmitsFullyAppliedParticipants() throws Exception {
        String planId = createPlan();
        String target = createParticipant(planId, "Anna", "Svensson", null);
        String withOneUnapplied = createParticipant(planId, "Bertil", "Karlsson", "Vill gärna spela med Anna Svensson.");
        String fullyApplied = createParticipant(planId, "Cecilia", "Doe", "Ny i klubben.");

        mockMvc.perform(put("/api/plans/" + planId + "/participants/" + fullyApplied + "/field-values")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newToClub\": true}"))
                .andExpect(status().isOk());

        List<se.klubb.groupplanner.suggest.ParticipantSuggestionCount> counts = commentSuggestionService.suggestionCountsForPlan(planId);
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).participantId()).isEqualTo(withOneUnapplied);
        assertThat(counts.get(0).suggestionCount()).isEqualTo(1);
        assertThat(target).isNotBlank();
    }

    /** WP2 privacy contract: nothing this service or its endpoints touch may persist the comment
     *  text anywhere - same scan style as {@code CommentLeakExportTest}, applied to every {@code
     *  *_json} column across the whole schema rather than one export/snapshot format. */
    @Test
    void sentinelCommentNeverAppearsInAnyJsonColumnAfterCallingTheEndpoints() throws Exception {
        String planId = createPlan();
        String target = createParticipant(planId, "Anna", "Svensson", null);
        String sentinel = "KANSLIG-WP2-TOLKNINGSFORSLAG-abc789";
        String source = createParticipant(planId, "Bertil", "Karlsson",
                "Vill gärna spela med Anna Svensson " + sentinel + ".");

        mockMvc.perform(get("/api/plans/" + planId + "/participants/" + source + "/comment-suggestions")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(sentinel)));
        mockMvc.perform(get("/api/plans/" + planId + "/comment-suggestions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());

        List<String> jsonColumns = jdbcClient.sql("""
                        SELECT m.name AS table_name, p.name AS column_name
                        FROM sqlite_master m
                        JOIN pragma_table_info(m.name) p
                        WHERE m.type = 'table' AND p.name LIKE '%json%'
                        """)
                .query((rs, rowNum) -> rs.getString("table_name") + "." + rs.getString("column_name"))
                .list();
        assertThat(jsonColumns).isNotEmpty();
        for (String tableDotColumn : jsonColumns) {
            String[] parts = tableDotColumn.split("\\.");
            List<String> values = jdbcClient.sql("SELECT " + parts[1] + " AS v FROM " + parts[0] + " WHERE " + parts[1] + " IS NOT NULL")
                    .query((rs, rowNum) -> rs.getString("v"))
                    .list();
            for (String value : values) {
                assertThat(value).as(tableDotColumn).doesNotContain(sentinel);
            }
        }

        // Defensive: confirm the sentinel really is in the raw column (positive control), and that
        // the earlier PLAY_WITH target participant created above was in fact wired up correctly.
        String raw = jdbcClient.sql("SELECT imported_comment FROM participant_profile WHERE id = :id")
                .param("id", source).query(String.class).single();
        assertThat(raw).contains(sentinel);
        assertThat(target).isNotBlank();
    }
}
