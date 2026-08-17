package se.klubb.groupplanner.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.TestSolverFactory;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.suggest.CommentSuggestion;
import se.klubb.groupplanner.suggest.CommentSuggestionService;
import se.klubb.groupplanner.suggest.SuggestionKind;

/**
 * WI-4 (v0.3.0 user feedback: "Ha demo-data för beachvolley så att man kan dema det utan att
 * importera en excelfil.") — {@link DemoDataService} must produce a complete, realistic, obviously-
 * fictional plan that (a) never leaks anything personnummer/contact-info-shaped, (b) carries exactly
 * the wish/comment shape the class javadoc documents, (c) is idempotent-safe to call repeatedly, and
 * (d) actually solves hard-feasible.
 */
@SpringBootTest
class DemoDataServiceTest {

    private static final int STEP_COUNT_LIMIT = 20_000;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private SolverInputAssembler solverInputAssembler;
    @Autowired
    private CommentSuggestionService commentSuggestionService;

    @Test
    void createsExpectedCountsOfEverything() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(result.planId());
        List<TimeSlot> slots = timeSlotRepository.findByActivityPlanId(result.planId());
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(result.planId());

        assertThat(participants).hasSize(DemoDataService.PLAYER_COUNT);
        assertThat(coaches).hasSize(DemoDataService.COACH_COUNT);
        assertThat(slots).hasSize(2);
        assertThat(groups).as("GroupGenerator must have produced groups").isNotEmpty();

        Map<String, Integer> wishCounts = countCustomFieldValuesByKey(result.planId(), participants);
        assertThat(wishCounts.getOrDefault("playWith", 0)).isEqualTo(25);
        assertThat(wishCounts.getOrDefault("mustPlayWith", 0)).isEqualTo(6);
        assertThat(wishCounts.getOrDefault("avoidPlayWith", 0)).isEqualTo(6);
        assertThat(wishCounts.getOrDefault("wantsCoach", 0)).isEqualTo(8);
        assertThat(wishCounts.getOrDefault("mustHaveCoach", 0)).isEqualTo(2);
        assertThat(wishCounts.getOrDefault("cannotHaveCoach", 0)).isEqualTo(1);
        assertThat(wishCounts.getOrDefault("cannotTimes", 0)).isEqualTo(1);
        assertThat(wishCounts.getOrDefault("newToClub", 0)).isEqualTo(2);
    }

    @Test
    void noPersonHasAnyPersonnummerOrRealLookingContactInfo() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(result.planId());

        for (ParticipantProfile p : participants) {
            assertNoContactInfo(personRepository.findById(p.personId()).orElseThrow());
        }
        for (CoachProfile c : coaches) {
            assertNoContactInfo(personRepository.findById(c.personId()).orElseThrow());
        }
    }

    private static void assertNoContactInfo(Person person) {
        // The `person` table has no personnummer column at all (verified against V1__core.sql) - this
        // asserts the next-closest thing: no email/phone/external id that could look like real contact
        // info or a source-system identifier ever gets set for demo data.
        assertThat(person.email()).isNull();
        assertThat(person.phone()).isNull();
        assertThat(person.externalId()).isNull();
    }

    @Test
    void commentsOnlyOnExpectedPlayersAndInternalNoteNeverSet() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());

        long withComment = participants.stream().filter(p -> p.importedComment() != null).count();
        // 8 original DEMO_COMMENTS (indices 52-59) + 5 WP2 DEMO_SUGGESTION_COMMENTS (indices 63-67).
        assertThat(withComment).isEqualTo(13);
        assertThat(participants).allSatisfy(p -> assertThat(p.internalNote()).isNull());
    }

    @Test
    void previousGroupNameIsSetForMostParticipantsButNullForNewToClubIndices() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());

        // previousGroupLevel is never set by the demo (WP1: only the derived group NAME is).
        assertThat(participants).allSatisfy(p -> assertThat(p.previousGroupLevel()).isNull());

        long withPreviousGroup = participants.stream().filter(p -> p.previousGroupName() != null).count();
        // 100 participants minus the 2 "new to club" indices.
        assertThat(withPreviousGroup).isEqualTo(DemoDataService.PLAYER_COUNT - 2);

        assertThat(participants).allSatisfy(p -> {
            if (p.previousGroupName() != null) {
                assertThat(p.previousGroupName()).matches("Torsdagsträning [1-8]");
            }
        });
    }

    @Test
    void secondInvocationCreatesASecondSeasonWithoutFailing() {
        DemoDataService.DemoResult first = demoDataService.createDemoSeason();
        DemoDataService.DemoResult second = demoDataService.createDemoSeason();

        assertThat(second.seasonId()).isNotEqualTo(first.seasonId());
        assertThat(second.planId()).isNotEqualTo(first.planId());
        assertThat(participantProfileRepository.findByActivityPlanId(second.planId())).hasSize(DemoDataService.PLAYER_COUNT);
    }

    @Test
    void createdPlanSolvesHardFeasible() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        GroupPlanSolution assembled = solverInputAssembler.assemble(result.planId()).solution();
        GroupPlanSolution solved = TestSolverFactory.solve(assembled, STEP_COUNT_LIMIT);

        HardMediumSoftLongScore score = solved.getScore();
        assertThat(score.hardScore())
                .as("the demo plan must always be solvable to hardScore == 0 (see DemoDataService's coach-capacity-math javadoc)")
                .isZero();
        // At least the deliberately always-unavailable participant (and, realistically, a few more
        // from raw capacity pressure - 8 groups x max 12 = 96 seats for 100 players) end up on the
        // waitlist: this demonstrates the waitlist/explainability feature by construction.
        assertThat(score.mediumScore()).as("at least the always-unavailable participant must be waitlisted").isLessThan(0);
    }

    /** WP2 "Tolkningsförslag" over the 5 dedicated demo participants (indices 63-67, {@code
     *  DemoDataService.DEMO_SUGGESTION_COMMENTS}) — every comment must resolve at HIGH confidence to
     *  exactly the kind it was written to demonstrate, since the referenced names were picked to be
     *  unique in the roster (see that field's javadoc). */
    @Test
    void demoSuggestionCommentsResolveToExpectedKinds() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());

        Map<String, ParticipantProfile> byComment = new HashMap<>();
        for (ParticipantProfile p : participants) {
            if (p.importedComment() != null) {
                byComment.put(p.importedComment(), p);
            }
        }

        assertExpectedKind(result.planId(), byComment, "Vill gärna spela med Cornelia Bäckman.", SuggestionKind.PLAY_WITH);
        assertExpectedKind(result.planId(), byComment, "Helst inte samma grupp som Cornelia Söderlund.", SuggestionKind.AVOID_PLAY_WITH);
        assertExpectedKind(result.planId(), byComment, "Kan inte torsdagar.", SuggestionKind.TIME_CANNOT);
        assertExpectedKind(result.planId(), byComment, "Vill ha Vera Nilsson som tränare.", SuggestionKind.COACH_WISH);
        assertExpectedKind(result.planId(), byComment, "Måste spela med Björn Wallin.", SuggestionKind.MUST_PLAY_WITH);
    }

    private void assertExpectedKind(
            String planId, Map<String, ParticipantProfile> byComment, String comment, SuggestionKind expectedKind) {
        ParticipantProfile participant = byComment.get(comment);
        assertThat(participant).as("no demo participant carries comment: " + comment).isNotNull();
        CommentSuggestion.ParticipantSuggestions result =
                commentSuggestionService.suggestionsForParticipant(planId, participant.id());
        assertThat(result.suggestions())
                .as("comment: " + comment)
                .anySatisfy(s -> {
                    assertThat(s.kind()).isEqualTo(expectedKind);
                    assertThat(s.confidence()).isEqualTo(CommentSuggestion.Confidence.HIGH);
                });
    }

    /** Review fix (minor 7): guards the two comment index ranges (DEMO_COMMENTS at {@code
     *  FIRST_COMMENT_INDEX}, DEMO_SUGGESTION_COMMENTS at {@code SUGGESTION_COMMENT_INDEX}) against
     *  ever overlapping again as either array grows - a silent overlap would mean one participant's
     *  index gets clobbered between the two comment sets ({@code commentForIndex} checks the legacy
     *  range first). Reflection, not a hand-copied literal range, so this test can't drift stale. */
    @Test
    void demoCommentAndSuggestionCommentIndexRangesNeverOverlap() throws Exception {
        int firstCommentIndex = readStaticInt("FIRST_COMMENT_INDEX");
        int demoCommentsLength = readStaticStringArray("DEMO_COMMENTS").length;
        int suggestionCommentIndex = readStaticInt("SUGGESTION_COMMENT_INDEX");
        int suggestionCommentsLength = readStaticStringArray("DEMO_SUGGESTION_COMMENTS").length;

        int commentsEnd = firstCommentIndex + demoCommentsLength; // exclusive
        int suggestionsEnd = suggestionCommentIndex + suggestionCommentsLength; // exclusive

        boolean overlap = firstCommentIndex < suggestionsEnd && suggestionCommentIndex < commentsEnd;
        assertThat(overlap)
                .as("DEMO_COMMENTS [%d,%d) must not overlap DEMO_SUGGESTION_COMMENTS [%d,%d)",
                        firstCommentIndex, commentsEnd, suggestionCommentIndex, suggestionsEnd)
                .isFalse();
    }

    private static int readStaticInt(String fieldName) throws Exception {
        var field = DemoDataService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static String[] readStaticStringArray(String fieldName) throws Exception {
        var field = DemoDataService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String[]) field.get(null);
    }

    private Map<String, Integer> countCustomFieldValuesByKey(String planId, List<ParticipantProfile> participants) {
        Map<String, FieldDefinition> fieldById = new HashMap<>();
        for (FieldDefinition field : fieldDefinitionRepository.findVisibleToPlan(planId)) {
            fieldById.put(field.id(), field);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (ParticipantProfile p : participants) {
            for (CustomFieldValue value : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, p.id())) {
                FieldDefinition field = fieldById.get(value.fieldDefinitionId());
                if (field == null) {
                    continue;
                }
                counts.merge(field.key(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
