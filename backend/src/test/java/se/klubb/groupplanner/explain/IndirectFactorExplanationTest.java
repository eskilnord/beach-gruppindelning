package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.explain.ExplanationDtos.AlternativeGroupView;
import se.klubb.groupplanner.explain.ExplanationDtos.BrokenWishView;
import se.klubb.groupplanner.explain.ExplanationDtos.IndirectFactorView;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.ExplanationRecordRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;

/**
 * v0.3.0 WI-5 — second-order ("via a coach") explanation coverage, user feedback: "Förklaringen av
 * varför en spelare blev tilldelad en grupp bör även visa om det beror på att en annan spelare
 * påverkas av en tränare." Covers both directions {@code ExplanationService} now derives directly
 * from {@code PersonPairWish}/{@code CoachWish}/{@code CoachSlot} facts (there is no Timefold match
 * type for a two-hop "X because Y because coach Z" chain):
 * <ul>
 *   <li>POSITIVE — {@link ExplanationDtos.PersonExplanationResponse#indirectFactors()}: a placed
 *       player is (partly) in their group because a same-group wish partner is themselves tied to
 *       that group via a MUST/WANT coach wish.
 *   <li>NEGATIVE — {@link BrokenWishView#coachBindingSv()}: a broken same-group wish annotated with
 *       WHY the partner couldn't move (they're coach-bound to their own group).
 *   <li>ALTERNATIVES — the {@code FRIEND_VIA_COACH} origin tag alongside {@code FRIEND_WISH}.
 * </ul>
 */
@SpringBootTest
class IndirectFactorExplanationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private ExplanationRecordRepository explanationRecordRepository;
    @Autowired
    private ExplanationService explanationService;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    // --- (a) + (d) positive direction, MUST vs WANT copy -----------------------------------

    @Test
    void indirectFactorPresentWhenWishPartnerIsCoachBoundToTheSameGroupViaMustWish() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupA, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith");
        fx.coachWish(lisa, anna, "mustHaveCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.indirectFactors()).hasSize(1);
        IndirectFactorView factor = response.indirectFactors().get(0);
        assertThat(factor.otherParticipantProfileId()).isEqualTo(lisa);
        assertThat(factor.otherPersonName()).isEqualTo("Lisa Larsson");
        assertThat(factor.coachPersonName()).isEqualTo("Anna Andersson");
        assertThat(factor.coachWishType()).isEqualTo("MUST");
        assertThat(factor.groupName()).isEqualTo("Grupp A");
        // Opus review finding 2: hedged copy ("kan delvis bero på") - the fact pattern is proven,
        // decisive influence is not, so the sentence must not overclaim.
        assertThat(factor.messageSv()).contains("Kalle Karlsson").contains("Lisa Larsson")
                .contains("kan delvis bero på")
                .contains("behöver tränaren Anna Andersson").contains("Grupp A");
        assertThat(factor.messageSv()).doesNotContain("är här delvis för att");

        // Opus review finding 6 (audit completeness): the persisted explanation_record row captures
        // the second-order reasons in its own V9 indirect_factors_json column.
        var audit = explanationRecordRepository.findByRunAndParticipant(runId, kalle).orElseThrow();
        assertThat(audit.indirectFactorsJson()).contains("Lisa Larsson").contains("Anna Andersson").contains("MUST");
    }

    @Test
    void indirectFactorUsesWantCopyForAWantCoachWish() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupA, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith");
        fx.coachWish(lisa, anna, "wantsCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.indirectFactors()).hasSize(1);
        IndirectFactorView factor = response.indirectFactors().get(0);
        assertThat(factor.coachWishType()).isEqualTo("WANT");
        assertThat(factor.messageSv()).contains("önskar tränaren Anna Andersson");
    }

    // --- (b) no coach wish -> no indirect factor --------------------------------------------

    @Test
    void noIndirectFactorWhenWishPartnerHasNoCoachWish() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith");
        // No coachWish for Lisa at all.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.indirectFactors()).isEmpty();
    }

    // --- (c) + (d) negative direction: broken-wish coach-binding annotation, MUST vs WANT ---

    @Test
    void brokenWishIsAnnotatedWithCoachBindingWhenPartnerIsMustBoundToAnotherGroup() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupB, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, broken: they're in different groups
        fx.coachWish(lisa, anna, "mustHaveCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        BrokenWishView wish = response.brokenWishes().stream()
                .filter(w -> "Lisa Larsson".equals(w.withPerson()))
                .findFirst()
                .orElseThrow();
        assertThat(wish.coachBindingSv()).isNotNull();
        assertThat(wish.coachBindingSv()).contains("Lisa Larsson").contains("Grupp B")
                .contains("Anna Andersson").contains("måste ha tränare");

        // Opus review finding 6 (audit completeness): coachBindingSv rides inside the audit row's
        // broken_preferences_json (it is a BrokenWishView field) - no dedicated column needed.
        var audit = explanationRecordRepository.findByRunAndParticipant(runId, kalle).orElseThrow();
        assertThat(audit.brokenPreferencesJson()).contains("coachBindingSv").contains("måste ha tränare");
    }

    @Test
    void brokenWishCoachBindingUsesWantCopyForAWantCoachWish() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupB, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");
        fx.coachWish(lisa, anna, "wantsCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        BrokenWishView wish = response.brokenWishes().stream()
                .filter(w -> "Lisa Larsson".equals(w.withPerson()))
                .findFirst()
                .orElseThrow();
        assertThat(wish.coachBindingSv()).contains("önskar tränare");
    }

    @Test
    void brokenWishHasNoCoachBindingWhenPartnerHasNoCoachTie() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        BrokenWishView wish = response.brokenWishes().stream()
                .filter(w -> "Lisa Larsson".equals(w.withPerson()))
                .findFirst()
                .orElseThrow();
        assertThat(wish.coachBindingSv()).isNull();
    }

    // --- (e) waitlisted target: no crash, indirectFactors empty -----------------------------

    @Test
    void waitlistedTargetHasEmptyIndirectFactorsAndDoesNotCrash() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupA, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, null); // waitlisted
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith");
        fx.coachWish(lisa, anna, "mustHaveCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.selectedGroup()).isNull();
        assertThat(response.waitlist()).isNotNull();
        assertThat(response.indirectFactors()).isEmpty();
    }

    // --- (f) FRIEND_VIA_COACH origin on the friend's alternative group -----------------------

    @Test
    void friendViaCoachOriginAppearsOnTheAlternativeForTheFriendsGroup() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupB, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");
        fx.coachWish(lisa, anna, "mustHaveCoach");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        AlternativeGroupView alternative = response.alternatives().stream()
                .filter(a -> a.groupId().equals(groupB))
                .findFirst()
                .orElseThrow();
        assertThat(alternative.origin()).contains("FRIEND_WISH", "FRIEND_VIA_COACH");
    }

    /** Opus review finding 1 (v0.3.0 WI-5): an AVOID wish (MUST_DIFFERENT/WANT_DIFFERENT) must tag
     * the other player's group with NEITHER FRIEND_WISH nor FRIEND_VIA_COACH — before the polarity
     * guard in {@code ExplanationService#unionOrigins}, "Kompisönskemål" (and, with a coach tie,
     * "Kompis knuten via tränare") steered the user toward the exact group the wish says to keep
     * the player away from. The coach tie is deliberately present here to prove the guard
     * suppresses BOTH tags, not just the friend one. */
    @Test
    void avoidWishTagsNeitherFriendWishNorFriendViaCoachOnTheOtherPlayersGroup() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1), null, null, 1);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.assignCoach(groupB, anna);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "avoidPlayWith"); // WANT_DIFFERENT - fulfilled, they ARE apart
        fx.coachWish(lisa, anna, "mustHaveCoach"); // coach tie exists but must stay irrelevant

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        AlternativeGroupView alternative = response.alternatives().stream()
                .filter(a -> a.groupId().equals(groupB))
                .findFirst()
                .orElseThrow();
        assertThat(alternative.origin()).doesNotContain("FRIEND_WISH");
        assertThat(alternative.origin()).doesNotContain("FRIEND_VIA_COACH");
        // And the avoid-partner never becomes a positive indirect factor either.
        assertThat(response.indirectFactors()).isEmpty();
    }

    @Test
    void friendWishOriginWithoutCoachTieDoesNotGetTheViaCoachTag() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 640.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        AlternativeGroupView alternative = response.alternatives().stream()
                .filter(a -> a.groupId().equals(groupB))
                .findFirst()
                .orElseThrow();
        assertThat(alternative.origin()).contains("FRIEND_WISH");
        assertThat(alternative.origin()).doesNotContain("FRIEND_VIA_COACH");
    }
}
