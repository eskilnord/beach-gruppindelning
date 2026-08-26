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
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.UnmetWishView;
import se.klubb.groupplanner.level.LevelService;
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
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * M-E2 {@code CausalNarrator} truthfulness pins (project's hardest rule, per this milestone's brief:
 * "every sentence must be provable from the probe data"). Hand-built fixtures for all six outcomes,
 * EXACT expected Swedish strings pinned character-for-character, plus a banned-lexicon sweep over a
 * real large-120 solve and the stale/BLOCKED_HARD structural rules.
 */
@SpringBootTest
class CausalNarrativeTruthfulnessTest {

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
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private SolveCoordinator solveCoordinator;
    @Autowired
    private ExplanationService explanationService;
    @Autowired
    private JdbcClient jdbcClient;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    private void setPreferTimes(String participantId, String timeSlotId) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("preferTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, "[\"" + timeSlotId + "\"]");
    }

    private String timeSlotIdOfBlock(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }

    private UnmetWishView wishOf(PersonExplanationResponse response, String wishId) {
        return response.unmetWishes().stream().filter(w -> w.wishId().equals(wishId)).findFirst()
                .orElseThrow(() -> new AssertionError("No unmet wish " + wishId + " in " + response.unmetWishes()));
    }

    // ─────────────────────────────────────────────────────────────────────── LOCKED

    @Test
    void lockedOutcomeIsExactWhenTheSolverWasNeverAllowedToTryAMove() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 5, 10, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.lockToGroup(kalle, groupA);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView friend = wishOf(response, "FRIEND:" + solverIdOf(response, lisa, fx));
        assertThat(friend.outcome()).isEqualTo("LOCKED");
        assertThat(friend.primaryReasonSv()).isEqualTo(
                "Kalle Karlsson är låst till Grupp A (Torsdag 18.00-19.30). Optimeringen fick inte flytta Kalle Karlsson, "
                        + "så önskemålet kunde inte prövas. Lås upp placeringen och kör om optimeringen om du vill att det ska testas.");
        // M-E2 review fix (MAJOR): LOCKED/NO_CANDIDATE now also carry the standard hedge sentence.
        assertThat(friend.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
        assertThat(response.lockedNoticeSv()).isEqualTo(
                "Kalle Karlsson är låst till Grupp A (Torsdag 18.00-19.30). Optimeringen fick inte flytta Kalle Karlsson till "
                        + "någon annan grupp, så alternativen nedan visar bara vad ett byte SKULLE innebära – inte vad optimeringen "
                        + "övervägde.");
    }

    /** {@code FRIEND:{id}} embeds the SOLVER-internal long id, which this test cannot predict from
     * outside — resolved once via the response's own candidate group / narrative text is not enough,
     * so instead we just scan for the one FRIEND wish present (there's exactly one in this fixture). */
    private long solverIdOf(PersonExplanationResponse response, String participantDbId, ExplanationTestFixture fx) {
        return response.unmetWishes().stream()
                .filter(w -> w.wishId().startsWith("FRIEND:"))
                .map(w -> Long.parseLong(w.wishId().substring("FRIEND:".length())))
                .findFirst()
                .orElseThrow();
    }

    // ─────────────────────────────────────────────────────────────────────── NO_CANDIDATE

    @Test
    void noCandidateOutcomeForATimeWishNoGroupOffers() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksOrphan = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1); // no group ever uses this slot.
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocksA.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksOrphan.get(0)));

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("NO_CANDIDATE");
        assertThat(time.primaryReasonSv()).isEqualTo(
                "Ingen grupp tränar Fredag 20.00-21.00 i den nuvarande tidsplaneringen, så tidsönskemålet kunde inte uppfyllas "
                        + "utan att schemat görs om.");
        assertThat(time.candidateGroupIds()).isEmpty();
        assertThat(time.bestCandidateGroupId()).isNull();
        // M-E2 review fix (MAJOR): LOCKED/NO_CANDIDATE now also carry the standard hedge sentence.
        assertThat(time.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
    }

    @Test
    void noCandidateOutcomeForAFriendWishWithAWaitlistedFriend() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(lisa, null); // waitlisted.
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView friend = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).findFirst().orElseThrow();
        assertThat(friend.outcome()).isEqualTo("NO_CANDIDATE");
        assertThat(friend.primaryReasonSv()).isEqualTo("Lisa Larsson är oplacerad (kölista), så önskemålet att spela med Lisa Larsson kunde inte uppfyllas.");
    }

    // ─────────────────────────────────────────────────────────────────────── BLOCKED_HARD

    @Test
    void blockedHardOutcomeWhenTheOnlyCandidateIsFull() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 1, 1, blocks.get(1)); // maxSize 1 - already full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, groupC); // Grupp C is now 1/1 - full.
        fx.wish(kalle, erik, "playWith"); // WANT_SAME, broken.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView friend = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).findFirst().orElseThrow();
        assertThat(friend.outcome()).isEqualTo("BLOCKED_HARD");
        assertThat(friend.primaryReasonSv()).isEqualTo(
                "Kalle Karlsson kunde inte flyttas till Grupp C (Torsdag 18.00-19.30) – gruppen är full (1/1). Ingen ändring "
                        + "av prioritetsordningen hjälper här – det krävs en plats till (höj maxstorleken eller flytta någon annan).");
        assertThat(friend.primaryReasonSv()).contains("Ingen ändring av prioritetsordningen hjälper");
        assertThat(friend.bestCandidateGroupId()).isNull();
        assertThat(friend.bestCandidateDelta()).isNull();
        assertThat(friend.competingReasons()).isEmpty();
    }

    /** M-E2 review fix (BLOCKER, per-blocker-family remedies + honest same-applies): the OLD tail
     * ("det krävs en plats till...") was hard-coded onto EVERY BLOCKED_HARD reason regardless of
     * family, and "Samma sak gäller" was appended for every OTHER candidate regardless of whether its
     * blocking reason actually matched. Here the FIRST candidate is TIME-blocked (must get the TIME
     * remedy, never the capacity one) and the second is FULL (a genuinely DIFFERENT reason, so it must
     * be listed on its own, never folded into a false "Samma sak gäller"). */
    @Test
    void blockedHardOutcomeGivesEachCandidateItsOwnFamilyRemedyAndNeverFalselyClaimsTheSameReason() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksBad = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        List<String> blocksFull = fx.addTimeSlotWithBlocks("Lördag 10.00-11.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocksA.get(0));
        String groupBad = fx.addGroup("Grupp Bad", 2, 0, 5, 10, blocksBad.get(0));
        String groupFull = fx.addGroup("Grupp Full", 3, 1, 1, 1, blocksFull.get(0)); // maxSize 1 - already full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setCanTimes(kalle, timeSlotIdOfBlock(blocksA.get(0))); // Kalle can ONLY attend Grupp A's own time.
        String maja = fx.addParticipant("Maja", "Majasson", 500.0, 3);
        fx.place(maja, groupA); // same group as Kalle right now.
        String filler = fx.addParticipant("Filler", "Fillersson", 500.0, 3);
        fx.place(filler, groupFull); // Grupp Full is now 1/1 - full.
        fx.wish(kalle, maja, "mustNotPlayWith"); // MUST_DIFFERENT, currently broken (same group).

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView avoid = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("AVOID:")).findFirst().orElseThrow();
        assertThat(avoid.outcome()).isEqualTo("BLOCKED_HARD");
        // TIME family remedy on the first (Grupp Bad) candidate - never the FULL-family tail.
        assertThat(avoid.primaryReasonSv())
                .contains("Kalle Karlsson kan inte den tiden – önskemålet kräver en annan tid eller ändrad tillgänglighet.");
        assertThat(avoid.primaryReasonSv()).doesNotContain("det krävs en plats till");
        // Grupp Full is a genuinely different reason - listed on its own, never "Samma sak gäller".
        assertThat(avoid.primaryReasonSv()).contains("Grupp Full").contains("gruppen är full (1/1)");
        assertThat(avoid.primaryReasonSv()).doesNotContain("Samma sak gäller");
    }

    /** M-E2 review fix (BLOCKER, "hard-feasibility from matches, not net delta"): a move that repairs
     * ONE hard violation (Kalle-Erik's broken must-play-with) while simultaneously creating a
     * DIFFERENT one (Kalle-Maja's must-NOT-play-with) nets to hard delta 0 - the OLD net-delta-based
     * {@code wouldBreakHard} would have read this as feasible; it must stay honestly BLOCKED_HARD. */
    @Test
    void aMoveThatRepairsOneHardViolationWhileBreakingAnotherIsHonestlyBlockedNotFeasible() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksZ = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocksA.get(0));
        String groupZ = fx.addGroup("Grupp Z", 2, 0, 5, 10, blocksZ.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, groupZ);
        String maja = fx.addParticipant("Maja", "Majasson", 500.0, 3);
        fx.place(maja, groupZ);
        fx.wish(kalle, erik, "mustPlayWith"); // MUST_SAME, currently broken - would be FIXED by moving to Z.
        fx.wish(kalle, maja, "mustNotPlayWith"); // MUST_DIFFERENT, currently satisfied - would be BROKEN by moving to Z.
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksZ.get(0))); // only Grupp Z matches Kalle's time preference.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("BLOCKED_HARD");
        assertThat(time.primaryReasonSv()).contains("måste-krav om spelpartner");
        assertThat(time.bestCandidateGroupId()).isNull();
        assertThat(time.bestCandidateDelta()).isNull();
    }

    /** M-E2 review fix (BLOCKER, "least-bad candidate ordering for the narrator"): Grupp X's move
     * REPAIRS Kalle's broken must-play-with-Erik hard wish (hard delta +1) on top of fixing the TIME
     * wish; Grupp Y only fixes the TIME wish (hard delta 0). The OLD |hard|-ascending ordering
     * (borrowed from {@code ImprovementSuggestionService}) would have preferred Y (|0| &lt; |1|),
     * discarding the hard-repairing candidate; the new ordering must prefer X. */
    @Test
    void solverMissPicksTheHardRepairingCandidateOverTheMerelySofterOneAsTheLeastBadCandidate() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksX = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        List<String> blocksY = fx.addTimeSlotWithBlocks("Lördag 10.00-11.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupX = fx.addGroup("Grupp X", 2, 0, 0, 10, blocksX.get(0));
        String groupY = fx.addGroup("Grupp Y", 3, 0, 0, 10, blocksY.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, groupX);
        fx.wish(kalle, erik, "mustPlayWith"); // MUST_SAME, currently broken - only fixed by moving to Grupp X.
        // Kalle prefers BOTH Grupp X's and Grupp Y's time - both are TIME candidates.
        setPreferTimesBoth(kalle, timeSlotIdOfBlock(blocksX.get(0)), timeSlotIdOfBlock(blocksY.get(0)));

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("SOLVER_MISS");
        assertThat(time.bestCandidateGroupId()).isEqualTo(groupX);
        assertThat(time.bestCandidateDelta().hard()).isEqualTo(1);
    }

    /** M-E2 review fix (MAJOR, "scope honesty for NO_CANDIDATE"): coach assignments are frozen
     * planning variables for the single-move probe - the coach analogue of the TIME scope-honesty fix. */
    @Test
    void noCandidateOutcomeForACoachWishNoGroupHasTheCoach() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String anna = fx.addCoach("Anna", "Andersson");
        fx.coachWish(kalle, anna, "wantsCoach"); // no group in the plan has Anna assigned at all.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView coach = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("COACH:")).findFirst().orElseThrow();
        assertThat(coach.outcome()).isEqualTo("NO_CANDIDATE");
        assertThat(coach.primaryReasonSv()).isEqualTo(
                "Ingen grupp har tränaren Anna Andersson i den nuvarande tränarfördelningen, så tränarönskemålet kunde inte "
                        + "uppfyllas utan att tränarfördelningen görs om.");
        assertThat(coach.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
    }

    private void setCanTimes(String participantId, String timeSlotId) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("canTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, "[\"" + timeSlotId + "\"]");
    }

    private void setPreferTimesBoth(String participantId, String timeSlotIdA, String timeSlotIdB) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("preferTimes").orElseThrow().id();
        customFieldValueRepository.upsert(
                fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, "[\"" + timeSlotIdA + "\",\"" + timeSlotIdB + "\"]");
    }

    // ─────────────────────────────────────────────────────────────────────── EQUAL

    @Test
    void equalOutcomeWhenTwoOppositeWishesOfTheSameWeightExactlyCancel() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        // targetSize=0/minSize=0 on BOTH groups (M-E2 test trick): groupSizeTarget's contribution to
        // ANY single-player move between these two groups is then EXACTLY score-neutral in total (the
        // constraint is linear in each group's own size when its target is 0), so this move's ONLY
        // net effect is the two sameGroupSoft wishes below - a clean, provably-exact-zero fixture.
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        String maja = fx.addParticipant("Maja", "Majasson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(maja, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, maja, "playWith"); // WANT_SAME, currently satisfied (both in A) - breaks on move.
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, currently broken (different groups) - fixed by move to B.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView lisaWish = response.unmetWishes().stream()
                .filter(w -> w.wishId().startsWith("FRIEND:") && w.wishSv().contains("Lisa"))
                .findFirst().orElseThrow();
        assertThat(lisaWish.outcome()).isEqualTo("EQUAL");
        assertThat(lisaWish.primaryReasonSv()).isEqualTo(
                "En flytt till Grupp B (Fredag 20.00-21.00) skulle ge exakt samma totalpoäng och bryter ingen regel. "
                        + "Flera likvärdiga lösningar finns. Du kan flytta Kalle Karlsson manuellt utan att planen blir sämre.");
        assertThat(lisaWish.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
        assertThat(lisaWish.bestCandidateGroupId()).isNotNull();
        assertThat(lisaWish.bestCandidateDelta().hard()).isZero();
        assertThat(lisaWish.bestCandidateDelta().medium()).isZero();
        assertThat(lisaWish.bestCandidateDelta().soft()).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────── SOLVER_MISS

    @Test
    void solverMissOutcomeWhenABetterCandidateExistsAndNothingElseChanges() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0))); // prefers Grupp B's time - nothing else is affected.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("SOLVER_MISS");
        assertThat(response.stale()).isFalse();
        assertThat(time.primaryReasonSv()).isEqualTo(
                "En flytt till Grupp B (Fredag 20.00-21.00) skulle faktiskt förbättra planen. Optimeringen hittade inte den "
                        + "bästa lösningen den här gången – kör om optimeringen (gärna med längre tid), eller flytta Kalle Karlsson "
                        + "manuellt.");
        assertThat(time.bestCandidateGroupId()).isNotNull();
    }

    @Test
    void solverMissOutcomeLeadsWithTheStaleClauseWhenThePlanHasChangedSinceTheRun() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0)));

        String runId = fx.insertFinishedRun();
        fx.bumpRevision(); // makes the run stale relative to the plan's current revision.
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.stale()).isTrue();
        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("SOLVER_MISS");
        assertThat(time.primaryReasonSv()).isEqualTo(
                "Planen har ändrats sedan optimeringen kördes. Med dagens data kan svaret nedan vara annorlunda. "
                        + "En flytt till Grupp B (Fredag 20.00-21.00) skulle faktiskt förbättra planen. Optimeringen hittade inte "
                        + "den bästa lösningen den här gången – kör om optimeringen (gärna med längre tid), eller flytta Kalle "
                        + "Karlsson manuellt.");
        // The stale clause is the FIRST clause of the reason (M-E2 brief).
        assertThat(time.primaryReasonSv()).startsWith("Planen har ändrats sedan optimeringen kördes.");
    }

    /** M-E2 review fix (BLOCKER): the stale clause used to be hard-coded to the SOLVER_MISS branch
     * only - every OTHER outcome must get it too, since the probe map behind ALL of them is equally
     * stale. Reuses the BLOCKED_HARD fixture, just with a revision bump. */
    @Test
    void blockedHardOutcomeAlsoLeadsWithTheStaleClauseWhenThePlanHasChangedSinceTheRun() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 1, 1, blocks.get(1)); // maxSize 1 - already full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, groupC);
        fx.wish(kalle, erik, "playWith");

        String runId = fx.insertFinishedRun();
        fx.bumpRevision();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.stale()).isTrue();
        UnmetWishView friend = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).findFirst().orElseThrow();
        assertThat(friend.outcome()).isEqualTo("BLOCKED_HARD");
        assertThat(friend.primaryReasonSv()).startsWith("Planen har ändrats sedan optimeringen kördes.");
        assertThat(friend.primaryReasonSv()).contains("gruppen är full (1/1)");
    }

    // ─────────────────────────────────────────────────────────────────────── TRADE_OFF

    @Test
    void tradeOffOutcomeNamesTheDominantCompetingReasonWithoutRawNumbers() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, currently satisfied - breaks on move to B.
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0))); // TIME wish, fixed by move to B.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        UnmetWishView time = wishOf(response, "TIME");
        assertThat(time.outcome()).isEqualTo("TRADE_OFF");
        // "kvotord" (ratio words), never raw score numbers/percentages - the group time labels
        // themselves legitimately contain digits (e.g. "18.00-19.30"), so this checks the score-y
        // vocabulary specifically rather than a blanket "no digits anywhere" rule.
        assertThat(time.primaryReasonSv()).doesNotContain("%").doesNotContain("poäng");
        // M-E2 review fix (MAJOR, ratio honesty): 2400 (sameGroupSoft)/950 (timePreferenceSoft) ≈
        // 2.53, which lands in the 2.5-3.5 "drygt dubbelt så tungt som" band, not the old
        // Math.round-inflated "tre gånger så tungt som" (three times as heavy - false).
        assertThat(time.primaryReasonSv()).isEqualTo(
                "Kalle Karlsson står kvar i Grupp A (Torsdag 18.00-19.30) för att alternativet Grupp B (Fredag 20.00-21.00) "
                        + "kostar mer: en flytt dit skulle bryta kompisönskemålet med Lisa Larsson, som väger drygt dubbelt så "
                        + "tungt som tidsönskemålet.");
        assertThat(time.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
        assertThat(time.bestCandidateGroupId()).isNotNull();
        assertThat(time.competingReasons()).isNotEmpty();
        assertThat(time.competingReasons()).anyMatch(r -> r.key().equals("sameGroupSoft"));
        // Self-check invariant (structural, also covered by NoClaimWithoutProbeTest): the wish's own
        // key must be provably fixable by the named candidate.
        assertThat(time.bestCandidateDelta()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────── directed-wish attribution

    /** M-E2 review fix (BLOCKER): {@code PersonPairWish.aParticipantProfileId} is the wish OWNER —
     * when the explained player is only the TARGET (b-side), {@code wishSv} must attribute the wish to
     * its actual owner, never read as if the target themselves expressed it. */
    @Test
    void directedFriendWishAttributesOwnershipCorrectlyOnBothSidesOfTheDrawer() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 5, 10, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // Kalle (owner, a-side) wishes to play with Lisa (b-side).

        String runId = fx.insertFinishedRun();

        PersonExplanationResponse kalleResponse = explanationService.explainPerson(fx.planId, runId, kalle);
        UnmetWishView kalleWish = kalleResponse.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).findFirst().orElseThrow();
        assertThat(kalleWish.wishSv()).isEqualTo("Kalle Karlsson vill helst spela med Lisa Larsson");

        PersonExplanationResponse lisaResponse = explanationService.explainPerson(fx.planId, runId, lisa);
        UnmetWishView lisaWish = lisaResponse.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).findFirst().orElseThrow();
        // Lisa never expressed this wish - it must never read "Lisa vill helst/måste ...".
        assertThat(lisaWish.wishSv()).doesNotContain("Lisa Larsson vill").doesNotContain("Lisa Larsson måste");
        assertThat(lisaWish.wishSv()).isEqualTo("Kalle Karlssons önskemål att spela med Lisa Larsson");
    }

    // ─────────────────────────────────────────────────────────────────────── dedupe

    /** M-E2 review fix (MAJOR): a MUTUAL playWith wish (both participants point the field at each
     * other) must yield exactly ONE unmet-wish entry, not two near-identical ones. */
    @Test
    void mutualPlayWithWishIsDedupedToOneUnmetWishEntry() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 5, 10, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");
        fx.wish(lisa, kalle, "playWith"); // mutual - both sides recorded the same wish independently.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        long friendWishCount = response.unmetWishes().stream().filter(w -> w.wishId().startsWith("FRIEND:")).count();
        assertThat(friendWishCount).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────── banned lexicon sweep

    @Test
    void noGeneratedSentenceEverUsesTheBannedLexicon() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("large-120");
        SolveCoordinator.GreedyResult greedy = solveCoordinator.runGreedy(planId);

        int checked = 0;
        for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(planId)) {
            PersonExplanationResponse response = explanationService.explainPerson(planId, greedy.runId(), p.id());
            // M-E2 review fix (MINOR, "banned-lexicon sweep extended"): every OTHER finished-Swedish-
            // sentence field, not just primaryReasonSv/hedgeSv.
            checkNotBanned(response.placementSummarySv(), "placementSummarySv", p.id());
            checkNotBanned(response.lockedNoticeSv(), "lockedNoticeSv", p.id());
            for (UnmetWishView wish : response.unmetWishes()) {
                checked++;
                checkNotBanned(wish.primaryReasonSv(), "primaryReasonSv for wish " + wish.wishId(), p.id());
                checkNotBanned(wish.hedgeSv(), "hedgeSv for wish " + wish.wishId(), p.id());
                checkNotBanned(wish.wishSv(), "wishSv for wish " + wish.wishId(), p.id());
                for (var competing : wish.competingReasons()) {
                    checkNotBanned(competing.messageSv(), "competingReasons[].messageSv for wish " + wish.wishId(), p.id());
                }
            }
        }
        assertThat(checked).isGreaterThan(0);
    }

    private void checkNotBanned(String text, String fieldLabel, String participantId) {
        if (text == null) {
            return;
        }
        for (String banned : CausalNarrator.BANNED_LEXICON) {
            assertThat(text.toLowerCase())
                    .as("%s of participant %s must not contain banned phrase '%s'", fieldLabel, participantId, banned)
                    .doesNotContain(banned.toLowerCase());
        }
    }
}
