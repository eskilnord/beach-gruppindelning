package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.explain.ExplanationDtos.ImprovementSuggestionsResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.SuggestionView;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.resources.TrainingBlockView;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Backend tests for WI-D "Förbättringsförslag" ({@link ImprovementSuggestionService}, user feedback
 * v0.4 #2) — one scenario per suggestion {@code kind}, plus cap/omittedCount, staleness, and
 * determinism, mirroring {@link WaitlistExplanationTest}'s style: {@link ExplanationTestFixture}
 * writes an EXACT, hand-crafted final placement (real repos, no long solves) and a plain {@code
 * FINISHED} run row, then the service under test is called directly (no HTTP layer) so assertions
 * can be precise about which candidate group/coach/kind was chosen.
 */
@SpringBootTest
class ImprovementSuggestionServiceTest {

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
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private ImprovementSuggestionService improvementSuggestionService;
    @Autowired
    private ImprovementSuggestionCache improvementSuggestionCache;
    @Autowired
    private JdbcClient jdbcClient;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    /** Like {@link ExplanationTestFixture#addTimeSlotWithBlocks}, but with an explicit start/end so
     *  tests needing two GENUINELY non-overlapping slots (coach-overlap scenarios) aren't stuck with
     *  the fixture's hardcoded 18:00-19:30. */
    private List<String> addTimeSlotWithBlocks(ExplanationTestFixture fx, String label, String start, String end, int courts) {
        TimeSlot slot = timeSlotRepository.insert(new TimeSlot(Uuid7.generate(), fx.planId, "THURSDAY", null, start, end, null, label));
        List<TrainingBlockView> blocks = trainingBlockGenerationService.generateForSlot(slot, courts);
        return blocks.stream().map(TrainingBlockView::id).toList();
    }

    private String timeSlotIdOfBlock(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }

    private void cannotAttend(String participantProfileId, String timeSlotId) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("cannotTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantProfileId, "[\"" + timeSlotId + "\"]");
    }

    private void setCoachBand(String coachProfileId, double min, double max) {
        CoachProfile p = coachProfileRepository.findById(coachProfileId).orElseThrow();
        coachProfileRepository.update(new CoachProfile(p.id(), p.personId(), p.activityPlanId(), null, min, max, null, null, false, null));
    }

    private void setCoachMaxGroupsPerWeek(String coachProfileId, int max) {
        CoachProfile p = coachProfileRepository.findById(coachProfileId).orElseThrow();
        coachProfileRepository.update(new CoachProfile(p.id(), p.personId(), p.activityPlanId(), null, null, null, null, max, false, null));
    }

    private void markUnavailable(String coachProfileId, String timeSlotId) {
        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coachProfileId, timeSlotId, CoachTimeSlot.UNAVAILABLE));
    }

    // ─────────────────────────────────────────────────────────────────────── (i) PLAYER_TIME

    @Test
    void waitlistedPlayerBlockedOnlyByTimeYieldsPlayerTimeSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        fx.place(m1, groupA); // 1/5 - plenty of room, so time is the ONLY problem for Erik.

        String erik = fx.addParticipant("Erik", "Eriksson", 605.0, 2);
        fx.place(erik, null);
        cannotAttend(erik, timeSlotIdOfBlock(blocks.get(0)));

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("PLAYER_TIME");
        assertThat(s.groupId()).isEqualTo(groupA);
        assertThat(s.participantProfileId()).isEqualTo(erik);
        assertThat(s.titleSv()).contains("Erik Eriksson").contains("Grupp A");
        assertThat(s.impactSv()).isEqualTo("1 spelare färre på kölistan");
        assertThat(response.omittedCount()).isZero();
        assertThat(response.runId()).isEqualTo(runId);
        assertThat(response.stale()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────── (ii) GROUP_MAX

    @Test
    void waitlistedPlayerBlockedOnlyByMaxSizeYieldsGroupMaxSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 2, 2, blocks.get(0));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // 2/2 - full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 600.0, 2);
        fx.place(kalle, null);

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("GROUP_MAX");
        assertThat(s.groupId()).isEqualTo(groupA);
        assertThat(s.participantProfileId()).isEqualTo(kalle);
        // User feedback v0.4.1: GROUP_MAX is a limitation explanation ("Grupp X är full"), not an
        // actionable imperative ("Öka maxstorleken") - court capacity can't actually be changed.
        assertThat(s.titleSv()).contains("Grupp A är full (max 2)").contains("Kalle Karlsson");
        assertThat(s.impactSv()).isEqualTo("hindrar 1 spelare från en plats");
    }

    @Test
    void multipleWaitlistedPlayersSameGroupMergeIntoOneGroupMaxSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 2, 2, blocks.get(0));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // 2/2 - full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 600.0, 2);
        String anna = fx.addParticipant("Anna", "Andersson", 600.0, 2);
        fx.place(kalle, null);
        fx.place(anna, null);

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("GROUP_MAX");
        assertThat(s.groupId()).isEqualTo(groupA);
        assertThat(s.participantProfileId()).isNull(); // ambiguous for the merged (N>1) case.
        // Opus review FIX 1: a merged suggestion for 2 waitlisted players must name BOTH as blocked
        // by the group's fullness, or its claim is false.
        assertThat(s.titleSv()).contains("Grupp A är full (max 2)").contains("hindrar 2 spelare från platser");
        assertThat(s.impactSv()).isEqualTo("hindrar 2 spelare från platser");
        assertThat(s.detailSv()).contains("Kalle Karlsson").contains("Anna Andersson");
    }

    // ─────────────────────────────────────────────────────────────────────── (iii) COACH_TIME

    @Test
    void coachlessGroupWithLevelFittingUnavailableCoachYieldsCoachTimeSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1); // requiredCoachCount=1

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // mean level 600.

        String lisa = fx.addCoach("Lisa", "Larsson");
        setCoachBand(lisa, 500.0, 700.0); // fits the group's 600 mean.
        String timeSlotId = timeSlotIdOfBlock(blocks.get(0));
        markUnavailable(lisa, timeSlotId);

        // A second coach whose band does NOT fit - must never be suggested.
        String pelle = fx.addCoach("Pelle", "Persson");
        setCoachBand(pelle, 0.0, 100.0);
        markUnavailable(pelle, timeSlotId);

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("COACH_TIME");
        assertThat(s.groupId()).isEqualTo(groupA);
        assertThat(s.coachProfileId()).isEqualTo(lisa);
        assertThat(s.titleSv()).contains("Lisa Larsson").contains("Grupp A");
        assertThat(s.impactSv()).isEqualTo("1 grupp utan tränare åtgärdas");
    }

    // ─────────────────────────────────────────────────────────────────────── (iv) COACH_MAX

    @Test
    void coachAtMaxGroupsCapYieldsCoachMaxSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = addTimeSlotWithBlocks(fx, "Torsdag A", "18:00", "19:30", 1);
        List<String> blocksB = addTimeSlotWithBlocks(fx, "Torsdag B", "20:00", "21:30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocksA.get(0), null, null, 1);
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocksB.get(0), null, null, 1);

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // groupA mean level 600, coachless.

        String lisa = fx.addCoach("Lisa", "Larsson");
        setCoachBand(lisa, 500.0, 700.0);
        setCoachMaxGroupsPerWeek(lisa, 1);
        fx.assignCoach(groupB, lisa); // uses up her one slot of capacity; groupB is NOT coachless.

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("COACH_MAX");
        assertThat(s.groupId()).isEqualTo(groupA);
        assertThat(s.coachProfileId()).isEqualTo(lisa);
        assertThat(s.titleSv()).contains("Lisa Larsson").contains("max nu 1").contains("Grupp A");
    }

    // ─────────────────────────────────────────── (iii/iv guards) family B HARD-constraint exclusions

    /** Opus review FIX 2a: a level-fitting coach who is ALSO a participant placed in a group whose
     *  block overlaps the coachless group's block must never be suggested — assigning them would
     *  trade the missing-coach HARD violation for a coachCannotTrainAndCoachSameTime (§10.17) one.
     *  Identical setup to the plain COACH_TIME scenario above except for the coach's second role,
     *  so the empty result is attributable to exactly that exclusion. */
    @Test
    void coachWhoAlsoPlaysAtAnOverlappingTimeIsNeverSuggested() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2); // 2 courts, SAME slot - overlapping blocks.
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1); // coachless.
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // mean level 600.

        // Lisa PLAYS in Grupp B (same time slot as Grupp A) AND is a coach whose band fits Grupp A.
        String lisaParticipant = fx.addParticipant("Lisa", "Larsson", 600.0, 3);
        fx.place(lisaParticipant, groupB);
        String lisaPersonId = participantProfileRepository.findById(lisaParticipant).orElseThrow().personId();
        String lisaCoach = coachProfileRepository.insert(new CoachProfile(
                Uuid7.generate(), lisaPersonId, fx.planId, null, 500.0, 700.0, null, null, true, null)).id();
        markUnavailable(lisaCoach, timeSlotIdOfBlock(blocks.get(0)));

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).isEmpty();
    }

    /** Opus review FIX 2b: a level-fitting coach forbidden by a CANNOT coach wish held by a player
     *  CURRENTLY in the target group must never be suggested — assigning them would trade the
     *  missing-coach HARD violation for a coachWishForbidden (§10.21c) one. Same attribution logic
     *  as the test above: identical to the plain COACH_TIME scenario except for the wish. */
    @Test
    void coachForbiddenByATargetGroupMemberIsNeverSuggested() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0), null, null, 1); // coachless.

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // mean level 600.

        String lisa = fx.addCoach("Lisa", "Larsson");
        setCoachBand(lisa, 500.0, 700.0);
        markUnavailable(lisa, timeSlotIdOfBlock(blocks.get(0)));
        fx.coachWish(m1, lisa, "cannotHaveCoach"); // CANNOT -> CoachWishType.CANNOT (coachWishForbidden).

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────── (v) GROUP_MAX_WISH dedup

    @Test
    void brokenWantSameWishBlockedByMaxSizeDedupesWithExistingGroupMaxSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = addTimeSlotWithBlocks(fx, "Torsdag A", "18:00", "19:30", 1);
        List<String> blocksB = addTimeSlotWithBlocks(fx, "Torsdag B", "20:00", "21:30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 1, 1, blocksA.get(0)); // max 1 - full with just Kalle.
        String groupB = fx.addGroup("Grupp B", 2, 1, 2, 2, blocksB.get(0)); // room for one more.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 600.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 600.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, broken - different groups.

        // A waitlisted player independently blocked only by groupA's max size - the pre-existing
        // GROUP_MAX suggestion the wish-pair fix below must dedupe/merge into (task brief: "a group
        // gets ONE max-size suggestion").
        String erik = fx.addParticipant("Erik", "Eriksson", 600.0, 2);
        fx.place(erik, null); // groupB has room (1/2), so erik's only ACTIONABLE blocker is groupA's max.

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(1);
        SuggestionView s = response.suggestions().get(0);
        assertThat(s.kind()).isEqualTo("GROUP_MAX");
        assertThat(s.groupId()).isEqualTo(groupA);
        // Opus review FIX 1: one waitlisted player (Erik) + one wish pair (Kalle/Lisa) both blocked
        // by the SAME group's fullness - the title must name every beneficiary, not just one.
        assertThat(s.titleSv()).contains("Grupp A är full (max 1)").contains("Erik Eriksson").contains("spela ihop");
        assertThat(s.impactSv()).contains("hindrar 1 spelare från en plats").contains("Kalle Karlsson").contains("Lisa Larsson").contains("spela ihop");
    }

    // ─────────────────────────────────────────────────────────────────────── (vi) cap + omittedCount

    @Test
    void capsAtTenSuggestionsAndReportsOmittedCount() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 50, 50, blocks.get(0)); // never full.
        String timeSlotId = timeSlotIdOfBlock(blocks.get(0));

        for (int i = 0; i < 12; i++) {
            String p = fx.addParticipant("P" + i, "Testsson", 600.0, 3);
            fx.place(p, null);
            cannotAttend(p, timeSlotId);
        }

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).hasSize(10);
        assertThat(response.omittedCount()).isEqualTo(2);
        assertThat(response.suggestions()).allMatch(s -> "PLAYER_TIME".equals(s.kind()) && groupA.equals(s.groupId()));
    }

    // ─────────────────────────────────────────────────────────────────────── (vii) staleness

    @Test
    void staleAfterPlanRevisionBump() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));

        String runId = fx.insertFinishedRun();
        int revisionAtRun = fx.currentRevision();
        fx.bumpRevision();

        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.stale()).isTrue();
        assertThat(response.basedOnRevision()).isEqualTo(revisionAtRun);
        assertThat(response.currentRevision()).isEqualTo(revisionAtRun + 1);
    }

    // ─────────────────────────────────────────────────────────────────────── (viii) determinism

    @Test
    void twoIndependentComputationsForTheSameRunAreIdentical() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = addTimeSlotWithBlocks(fx, "Torsdag A", "18:00", "19:30", 1);
        List<String> blocksB = addTimeSlotWithBlocks(fx, "Torsdag B", "20:00", "21:30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocksA.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocksB.get(0), null, null, 1); // coachless.

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        fx.place(m1, groupA);
        String m2 = fx.addParticipant("M2", "M", 600.0, 3);
        fx.place(m2, groupB);

        String timeSlotIdA = timeSlotIdOfBlock(blocksA.get(0));
        String erik = fx.addParticipant("Erik", "Eriksson", 600.0, 2);
        fx.place(erik, null);
        cannotAttend(erik, timeSlotIdA);
        String anna = fx.addParticipant("Anna", "Andersson", 600.0, 2);
        fx.place(anna, null);
        cannotAttend(anna, timeSlotIdA);

        String lisa = fx.addCoach("Lisa", "Larsson");
        setCoachBand(lisa, 500.0, 700.0);
        markUnavailable(lisa, timeSlotIdOfBlock(blocksB.get(0)));

        String runId = fx.insertFinishedRun();

        ImprovementSuggestionsResponse first = improvementSuggestionService.suggestions(fx.planId, runId);
        improvementSuggestionCache.clear(); // force a fully independent second computation, not a cache hit.
        ImprovementSuggestionsResponse second = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(second).isEqualTo(first);
        assertThat(first.suggestions()).isNotEmpty();
    }
}
