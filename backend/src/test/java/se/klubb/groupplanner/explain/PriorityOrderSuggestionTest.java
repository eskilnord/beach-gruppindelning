package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.explain.ExplanationDtos.ImprovementSuggestionsResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.SuggestionView;
import se.klubb.groupplanner.fields.ConstraintWeightOverrideRequest;
import se.klubb.groupplanner.fields.ConstraintWeightService;
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
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * v0.6.0 E5 "family D" ({@link PriorityOrderSuggestionBuilder}) tests — mirrors {@link
 * ImprovementSuggestionServiceTest}'s style (hand-crafted {@link ExplanationTestFixture} placements, a
 * plain {@code FINISHED} run row, direct service calls). The core flipping fixture below duplicates
 * (twice, for two independent players) the EXACT numeric scenario {@code
 * CausalNarrativeTruthfulnessTest#tradeOffOutcomeNamesTheDominantCompetingReasonWithoutRawNumbers} and
 * {@code PrioritySensitivityCalculatorTest#flipsByReorderAtRankOneWhenOnlyTheFullPromotionIsEnough}
 * already pin (a broken {@code sameGroupSoft} wish worth -2400, a {@code timePreferenceSoft} wish worth
 * +950 under the default order) — both groups sized {@code targetSize=0/minSize=0/maxSize=10} (that
 * test's own "M-E2 test trick") so {@code groupSizeTarget}'s contribution is exactly zero and the ONLY
 * two nonzero bucket deltas are the two named above, making every count/order in this file
 * hand-derivable rather than solver-dependent.
 */
@SpringBootTest
class PriorityOrderSuggestionTest {

    private static final Logger log = LoggerFactory.getLogger(PriorityOrderSuggestionTest.class);

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
    private ImprovementSuggestionService improvementSuggestionService;
    @Autowired
    private ExplanationService explanationService;
    @Autowired
    private ConstraintWeightService constraintWeightService;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    private void setPreferTimes(String participantId, String timeSlotId) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("preferTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, se.klubb.groupplanner.domain.CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId,
                "[\"" + timeSlotId + "\"]");
    }

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    private String jdbcTimeSlotId(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }

    /** Adds one "Kalle stands to lose a satisfied friend wish but gain a time wish" pair, sized exactly
     *  like the CausalNarrativeTruthfulnessTest/PrioritySensitivityCalculatorTest fixtures referenced in
     *  the class javadoc — returns Kalle's participantProfileId (the one qualifying player; Lisa's own
     *  friend wish is already satisfied from HER perspective, so she never becomes a qualifying player
     *  herself). {@code groupOrderStart} lets callers place multiple independent pairs in one plan
     *  without groupOrder collisions. */
    private String addFlippingPair(ExplanationTestFixture fx, int groupOrderStart, String label) {
        List<String> blocksA = fx.addTimeSlotWithBlocks(label + " A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks(label + " B", 1);
        String groupA = fx.addGroup(label + " Grupp A", groupOrderStart, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup(label + " Grupp B", groupOrderStart + 1, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle" + groupOrderStart, "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa" + groupOrderStart, "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, currently satisfied - breaks on move to B.
        setPreferTimes(kalle, jdbcTimeSlotId(blocksB.get(0))); // TIME wish, fixed by move to B.
        return kalle;
    }

    /** FIX 5 (E5 review, adjacent-swap alternative) test support: sets {@code previousGroupName} so
     *  {@code SolverInputAssembler#previousGroupOrderOf} resolves it to {@code groupOrder} (a bare 1-2
     *  digit string is a trailing-standalone-integer match, {@code PreviousGroupNormalizer} rule (a)). */
    private void setPreviousGroupName(String participantProfileId, int groupOrder) {
        ParticipantProfile p = participantProfileRepository.findById(participantProfileId).orElseThrow();
        participantProfileRepository.update(new ParticipantProfile(
                p.id(), p.personId(), p.activityPlanId(), p.rankingPoints(), p.rankingSource(), String.valueOf(groupOrder),
                p.previousGroupLevel(), p.estimatedLevel(), p.levelConfidence(), p.manualLevelScore(), p.importedComment(),
                p.internalNote(), p.manualReviewFlag(), p.waitlisted(), p.reviewedDone()));
    }

    /** FIX 5 companion to {@link #addFlippingPair}: adds a player (Erik) whose currently-SATISFIED
     *  TRAIN_TOGETHER wish (playWith Sven) would break on a move that instead SATISFIES their
     *  PREVIOUS_GROUP wish — an INDEPENDENT flip axis (flips iff PREVIOUS_GROUP outranks
     *  TRAIN_TOGETHER, by the exact same per-band arithmetic {@link #addFlippingPair}'s own class
     *  javadoc derives for PREFERRED_TIME vs TRAIN_TOGETHER) needed so the single-adjacent-swap
     *  suggestion (b) can actually flip someone while the best (a) still needs a 2-swap order. Returns
     *  Erik's participantProfileId. */
    private String addPreviousGroupVsTrainTogetherPair(ExplanationTestFixture fx, int groupOrderStart, String label) {
        List<String> blocksA = fx.addTimeSlotWithBlocks(label + " A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks(label + " B", 1);
        String groupA = fx.addGroup(label + " Grupp A", groupOrderStart, 0, 0, 10, blocksA.get(0));
        int groupBOrder = groupOrderStart + 1;
        String groupB = fx.addGroup(label + " Grupp B", groupBOrder, 0, 0, 10, blocksB.get(0));

        String erik = fx.addParticipant("Erik" + groupOrderStart, "Eriksson", 500.0, 3);
        String sven = fx.addParticipant("Sven" + groupOrderStart, "Svensson", 500.0, 3);
        fx.place(erik, groupA);
        fx.place(sven, groupA);
        fx.wish(erik, sven, "playWith"); // WANT_SAME, currently satisfied - breaks on move to B.
        setPreviousGroupName(erik, groupBOrder); // PREVIOUS_GROUP wish, fixed by move to B.
        return erik;
    }

    // ─────────────────────────────────────────────────────────────────────── flip present, exact counts + order

    @Test
    void reorderThatFlipsTwoPlayersWishesProducesTheExactPredictedSuggestion() {
        ExplanationTestFixture fx = newFixture();
        addFlippingPair(fx, 1, "Par1");
        addFlippingPair(fx, 3, "Par2");

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        List<SuggestionView> priorityOrder = response.suggestions().stream().filter(s -> "PRIORITY_ORDER".equals(s.kind())).toList();
        assertThat(priorityOrder).hasSize(1);
        SuggestionView s = priorityOrder.get(0);
        assertThat(s.titleSv()).isEqualTo("Fler skulle kunna få sina önskemål uppfyllda med en annan prioritetsordning.");
        // Hand-derived (see class javadoc): the only flipping orders require PREFERRED_TIME ranked
        // above TRAIN_TOGETHER; the CLOSEST such order to the current [TRAIN_TOGETHER, PREVIOUS_GROUP,
        // PREFERRED_TIME, LEVEL] (fewest pairwise adjacent swaps - both single-swap candidates fail,
        // the first 2-swap order in ALL_ORDERS' own lexicographic enumeration is this one) is
        // [PREVIOUS_GROUP, PREFERRED_TIME, TRAIN_TOGETHER, LEVEL].
        assertThat(s.suggestedOrder()).containsExactly("PREVIOUS_GROUP", "PREFERRED_TIME", "TRAIN_TOGETHER", "LEVEL");
        assertThat(s.detailSv()).isEqualTo(
                "2 av 2 granskade spelare med ouppfyllda önskemål skulle var för sig kunna flyttas som önskat utan att "
                        + "planen blir sämre, om ordningen ändras till Tidigare grupp, Önskad träningstid, Träna tillsammans "
                        + "och Träningsnivå.");
        assertThat(s.impactSv()).isEqualTo("Vad optimeringen faktiskt väljer avgörs först när du kör om den.");
        // Never claims a placement - truthfulness rule (task brief).
        assertThat(s.titleSv()).doesNotContain("kommer att").doesNotContain("hamnar");
        assertThat(s.detailSv()).doesNotContain("kommer att").doesNotContain("hamnar");
        for (String banned : CausalNarrator.BANNED_LEXICON) {
            assertThat(s.titleSv().toLowerCase()).doesNotContain(banned.toLowerCase());
            assertThat(s.detailSv().toLowerCase()).doesNotContain(banned.toLowerCase());
            assertThat(s.impactSv().toLowerCase()).doesNotContain(banned.toLowerCase());
        }
        // Every reference id is null - a plan-level suggestion never fabricates a single group/
        // participant/coach/time-slot pointer (task brief: additive field, "null, never fabricated"
        // contract untouched).
        assertThat(s.groupId()).isNull();
        assertThat(s.participantProfileId()).isNull();
        assertThat(s.coachProfileId()).isNull();
        assertThat(s.timeSlotId()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────── singular participle (n==1)

    @Test
    void singleAnalyzedPlayerUsesTheSingularParticiple() {
        ExplanationTestFixture fx = newFixture();
        addFlippingPair(fx, 1, "Par1"); // exactly one qualifying, analyzed, flipping player.

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        List<SuggestionView> priorityOrder = response.suggestions().stream().filter(s -> "PRIORITY_ORDER".equals(s.kind())).toList();
        assertThat(priorityOrder).hasSize(1);
        SuggestionView s = priorityOrder.get(0);
        // "granskad" (singular), never "granskade" (plural), when analyzedPlayers==1.
        assertThat(s.detailSv()).startsWith("1 av 1 granskad spelare med ouppfyllda önskemål");
        assertThat(s.detailSv()).doesNotContain("1 granskade spelare");
    }

    // ─────────────────────────────────────────────────────────────────────── adjacent-swap alternative (b)

    @Test
    void adjacentSwapAlternativeRendersWhenItActuallyFlipsADifferentPlayerSet() {
        ExplanationTestFixture fx = newFixture();
        addFlippingPair(fx, 1, "Par1"); // flips iff PREFERRED_TIME outranks TRAIN_TOGETHER.
        addPreviousGroupVsTrainTogetherPair(fx, 3, "Par2"); // flips iff PREVIOUS_GROUP outranks TRAIN_TOGETHER.

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        List<SuggestionView> priorityOrder = response.suggestions().stream().filter(s -> "PRIORITY_ORDER".equals(s.kind())).toList();
        // Hand-derived (see toSuggestion/pickSuggestions and both fixtures' own flip conditions): the
        // two players' flip conditions overlap (both flip) only for orders where BOTH PREFERRED_TIME
        // and PREVIOUS_GROUP outrank TRAIN_TOGETHER - the fewest-swap such order from the current
        // [TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL] is [PREVIOUS_GROUP, PREFERRED_TIME,
        // TRAIN_TOGETHER, LEVEL] (2 swaps, flips BOTH players) - itself NOT one of the 3 single
        // adjacent swaps of the current order, satisfying "best != any adjacent swap". Among those 3
        // single adjacent swaps, only [PREVIOUS_GROUP, TRAIN_TOGETHER, PREFERRED_TIME, LEVEL] flips
        // anyone at all (Erik's PREVIOUS_GROUP-vs-TRAIN_TOGETHER pair alone, Kalle's pair does not),
        // making it suggestion (b).
        assertThat(priorityOrder).hasSize(2);

        SuggestionView best = priorityOrder.get(0);
        assertThat(best.suggestedOrder()).containsExactly("PREVIOUS_GROUP", "PREFERRED_TIME", "TRAIN_TOGETHER", "LEVEL");
        assertThat(best.detailSv()).isEqualTo(
                "2 av 2 granskade spelare med ouppfyllda önskemål skulle var för sig kunna flyttas som önskat utan att "
                        + "planen blir sämre, om ordningen ändras till Tidigare grupp, Önskad träningstid, Träna tillsammans "
                        + "och Träningsnivå.");

        SuggestionView adjacent = priorityOrder.get(1);
        assertThat(adjacent.suggestedOrder()).containsExactly("PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL");
        assertThat(adjacent.detailSv()).isEqualTo(
                "1 av 2 granskade spelare med ouppfyllda önskemål skulle var för sig kunna flyttas som önskat utan att "
                        + "planen blir sämre, om ordningen ändras till Tidigare grupp, Träna tillsammans, Önskad träningstid "
                        + "och Träningsnivå.");
    }

    // ─────────────────────────────────────────────────────────────────────── target-side wish never counted (BLOCKER)

    @Test
    void targetSideOfADirectedWishIsNeverCountedAsAQualifyingPlayer() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("F1 A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("F1 B", 1);
        String groupA = fx.addGroup("F1 Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("F1 Grupp B", 2, 0, 0, 10, blocksB.get(0));
        String kalle = fx.addParticipant("KalleF1", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("LisaF1", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // one-directional: Kalle OWNS the wish, Lisa is only its target.

        String runId = fx.insertFinishedRun();
        ExplanationService.RunContext ctx = explanationService.loadContext(fx.planId, runId);
        PriorityOrderSuggestionBuilder.Result result = PriorityOrderSuggestionBuilder.build(ctx, moveProbeBean());

        // Before the fix, BOTH Kalle (the wish's owner) and Lisa (only its directed b-side target)
        // qualified - inflating n to 2 for what is honestly one player's unmet wish. Only Kalle,
        // the wish's owner, may ever count.
        assertThat(result.totalAffectedPlayers()).isEqualTo(1);
    }

    @Test
    void aTargetOnlyWishWithNoQualifyingOwnerProducesNoFamilyDSuggestionAtAll() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("F1b A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("F1b B", 1);
        String groupA = fx.addGroup("F1b Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("F1b Grupp B", 2, 0, 0, 10, blocksB.get(0));
        String kalle = fx.addParticipant("KalleF1b", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("LisaF1b", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // Kalle owns; Lisa is only the wish's directed target.
        // Kalle pinned - excluded from collectQualifyingPlayers entirely (the "placed, non-pinned"
        // filter), leaving Lisa as the ONLY placed, non-pinned candidate, with an unmet wish she does
        // not own.
        fx.lockToGroup(kalle, groupA);

        String runId = fx.insertFinishedRun();
        ExplanationService.RunContext ctx = explanationService.loadContext(fx.planId, runId);
        PriorityOrderSuggestionBuilder.Result result = PriorityOrderSuggestionBuilder.build(ctx, moveProbeBean());

        assertThat(result.totalAffectedPlayers()).isZero();
        assertThat(result.suggestions()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────── wishGain self-check (MINOR)

    @Test
    void bestCandidateFixingADifferentPairOfTheSameKeyIsNeverCountedAsAFlip() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("F3 A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("F3 B", 1);
        String groupA = fx.addGroup("F3 Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("F3 Grupp B", 2, 0, 0, 10, blocksB.get(0));
        String kalle = fx.addParticipant("KalleF3", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("LisaF3", "Larsson", 500.0, 3);
        String moa = fx.addParticipant("MoaF3", "Moasson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        fx.place(moa, groupB);
        fx.wish(kalle, lisa, "playWith"); // Kalle's ONLY qualifying wish: candidateGroups=[groupB].

        String runId = fx.insertFinishedRun();
        ExplanationService.RunContext ctx = explanationService.loadContext(fx.planId, runId);

        long kalleId = ctx.solution().getPlayerAssignments().stream()
                .filter(pa -> pa.getDisplayName().startsWith("KalleF3"))
                .findFirst().orElseThrow().getId();
        long moaId = ctx.solution().getPlayerAssignments().stream()
                .filter(pa -> pa.getDisplayName().startsWith("MoaF3"))
                .findFirst().orElseThrow().getId();

        // Hand-crafted probe result: hard-feasible, net-negative soft delta (TRADE_OFF-shaped), but its
        // newlyFixedScored names sameGroupSoft fixed for (Kalle, Moa) - a DIFFERENT pair sharing the
        // SAME constraint key as Kalle's actual wish (Kalle, Lisa). The self-check must refuse to treat
        // this as fixing Kalle's own wish, so this player must contribute zero flips anywhere.
        MoveProbe.Result mismatchedResult = new MoveProbe.Result(
                HardMediumSoftLongScore.ofSoft(-50),
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new MoveProbe.ScoredMatch(
                        ConstraintKeys.SAME_GROUP_SOFT, "fejkad matchning för testet", HardMediumSoftLongScore.ofSoft(80),
                        List.of(kalleId, moaId))),
                List.of(),
                null,
                null);
        MoveProbe stubMoveProbe = new MoveProbe(null) {
            @Override
            public MoveProbe.Result evaluate(
                    GroupPlanSolution solution, ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis, PlayerAssignment target,
                    Group candidateGroupOrNull, SolutionIndex idx) {
                return mismatchedResult;
            }
        };

        PriorityOrderSuggestionBuilder.Result result = PriorityOrderSuggestionBuilder.build(ctx, stubMoveProbe);

        assertThat(result.totalAffectedPlayers()).isEqualTo(1);
        assertThat(result.analyzedPlayers()).isEqualTo(1); // still reviewed - just contributes zero flips.
        assertThat(result.suggestions()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────── correctness gate: already-free move

    @Test
    void aBrokenWantSamePairThatIsAlreadyFreeUnderCurrentWeightsProducesNoFamilyDSuggestion() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("F6 A", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("F6 B", 1);
        String groupA = fx.addGroup("F6 Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("F6 Grupp B", 2, 0, 0, 10, blocksB.get(0));
        String kalle = fx.addParticipant("KalleF6", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("LisaF6", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB);
        // Unmet WANT_SAME wish, placed in DIFFERENT (otherwise identical, uncontested) groups - moving
        // Kalle to Lisa's group only ever SATISFIES sameGroupSoft, with nothing else to trade off
        // (same level, no time preference, no previous-group wish, no coach requirement) - hard-feasible
        // and a pure gain (SOLVER_MISS-shaped: already free, zero cost, under CURRENT weights).
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).noneMatch(s -> "PRIORITY_ORDER".equals(s.kind()));
    }

    // ─────────────────────────────────────────────────────────────────────── customWeightsActive -> absent

    @Test
    void absentWhenThePlanUsesCustomWeights() {
        ExplanationTestFixture fx = newFixture();
        addFlippingPair(fx, 1, "Par1");
        addFlippingPair(fx, 3, "Par2");
        constraintWeightService.applyOverrides(
                fx.planId, List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", "SOFT", 777, true)));

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).noneMatch(s -> "PRIORITY_ORDER".equals(s.kind()));
    }

    // ─────────────────────────────────────────────────────────────────────── zero unmet bucket wishes -> absent

    @Test
    void absentWhenThePlanHasZeroUnmetBucketWishes() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocks.get(0));
        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA); // no wishes at all.

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        assertThat(response.suggestions()).noneMatch(s -> "PRIORITY_ORDER".equals(s.kind()));
    }

    // ─────────────────────────────────────────────────────────────────────── cap honesty

    @Test
    void capHonestyAppendsTheNotReviewedClauseWhenTheProbeBudgetIsExceeded() {
        int original = PriorityOrderSuggestionBuilder.maxPriorityProbes;
        PriorityOrderSuggestionBuilder.maxPriorityProbes = 2; // each pair costs exactly 1 probe (1 candidate group).
        try {
            ExplanationTestFixture fx = newFixture();
            addFlippingPair(fx, 1, "Par1");
            addFlippingPair(fx, 3, "Par2");
            addFlippingPair(fx, 5, "Par3"); // 3rd pair - never analyzed under the cap of 2.

            String runId = fx.insertFinishedRun();
            ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

            List<SuggestionView> priorityOrder = response.suggestions().stream().filter(s -> "PRIORITY_ORDER".equals(s.kind())).toList();
            assertThat(priorityOrder).hasSize(1);
            SuggestionView s = priorityOrder.get(0);
            assertThat(s.detailSv()).startsWith("2 av 2 granskade spelare");
            assertThat(s.detailSv()).endsWith("(1 spelare till har ouppfyllda önskemål men granskades inte.)");
        } finally {
            PriorityOrderSuggestionBuilder.maxPriorityProbes = original;
        }
    }

    // ─────────────────────────────────────────────────────────────────────── probe-count bound

    @Test
    void probesAtMostTheConfiguredCapAndZeroWhenSkipped() {
        ExplanationTestFixture fx = newFixture();
        addFlippingPair(fx, 1, "Par1");
        addFlippingPair(fx, 3, "Par2");
        String runId = fx.insertFinishedRun();
        ExplanationService.RunContext ctx = explanationService.loadContext(fx.planId, runId);

        PriorityOrderSuggestionBuilder.Result result =
                PriorityOrderSuggestionBuilder.build(ctx, moveProbeBean());
        assertThat(result.probeCount()).isLessThanOrEqualTo(PriorityOrderSuggestionBuilder.maxPriorityProbes);
        assertThat(result.probeCount()).isEqualTo(2); // one candidate group per player, two players.
        assertThat(result.totalAffectedPlayers()).isEqualTo(2);
        assertThat(result.analyzedPlayers()).isEqualTo(2);

        // Skipped (customWeightsActive) -> zero probes.
        constraintWeightService.applyOverrides(
                fx.planId, List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", "SOFT", 777, true)));
        ExplanationService.RunContext ctx2 = explanationService.loadContext(fx.planId, runId);
        PriorityOrderSuggestionBuilder.Result skipped = PriorityOrderSuggestionBuilder.build(ctx2, moveProbeBean());
        assertThat(skipped.probeCount()).isZero();
        assertThat(skipped.suggestions()).isEmpty();
    }

    @Autowired
    private MoveProbe moveProbeAutowired;

    private MoveProbe moveProbeBean() {
        return moveProbeAutowired;
    }

    // ─────────────────────────────────────────────────────────────────────── slot reservation (8 data + 2 priority)

    @Test
    void slotReservationShowsEightDataSuggestionsAndThePrioritySuggestionSeparately() {
        ExplanationTestFixture fx = newFixture();
        List<String> waitBlocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupWait = fx.addGroup("Grupp Wait", 20, 1, 50, 50, waitBlocks.get(0)); // never full.
        String waitTimeSlotId = jdbcTimeSlotId(waitBlocks.get(0));
        String waitFieldId = fieldDefinitionRepository.findGlobalByKey("cannotTimes").orElseThrow().id();
        for (int i = 0; i < 9; i++) {
            String p = fx.addParticipant("W" + i, "Waitsson", 600.0, 3);
            fx.place(p, null);
            customFieldValueRepository.upsert(
                    waitFieldId, se.klubb.groupplanner.domain.CustomFieldValue.ENTITY_TYPE_PARTICIPANT, p, "[\"" + waitTimeSlotId + "\"]");
        }
        addFlippingPair(fx, 30, "Par1");
        addFlippingPair(fx, 32, "Par2");

        String runId = fx.insertFinishedRun();
        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(fx.planId, runId);

        List<SuggestionView> dataSuggestions = response.suggestions().stream().filter(s -> !"PRIORITY_ORDER".equals(s.kind())).toList();
        List<SuggestionView> priorityOrder = response.suggestions().stream().filter(s -> "PRIORITY_ORDER".equals(s.kind())).toList();
        assertThat(dataSuggestions).hasSize(8);
        assertThat(response.omittedCount()).isEqualTo(1);
        assertThat(priorityOrder).hasSize(1);
        assertThat(response.suggestions()).hasSize(9); // 8 data + 1 priority.
    }

    // ─────────────────────────────────────────────────────────────────────── banned-lexicon sweep, large-120

    /** Extends {@code CausalNarrativeTruthfulnessTest#noGeneratedSentenceEverUsesTheBannedLexicon}'s
     *  sweep to {@code PRIORITY_ORDER} suggestions on a real large-120 solve. If the dataset happens to
     *  produce zero such suggestions (e.g. every unmet bucket wish there is hard-blocked, or the greedy
     *  solve's weights don't match a ladder permutation), this test still passes but degrades to a
     *  no-op on the sweep itself - the hand-fixture test above already pins non-banned exact text for a
     *  real {@code PRIORITY_ORDER} suggestion, so truthfulness coverage does not depend on large-120
     *  producing one. */
    @Test
    void noGeneratedPriorityOrderSentenceEverUsesTheBannedLexiconOnLarge120() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        String planId = loader.load("large-120");
        SolveCoordinator.GreedyResult greedy = solveCoordinator.runGreedy(planId);

        ImprovementSuggestionsResponse response = improvementSuggestionService.suggestions(planId, greedy.runId());
        int priorityOrderChecked = 0;
        for (SuggestionView s : response.suggestions()) {
            if (!"PRIORITY_ORDER".equals(s.kind())) {
                continue;
            }
            priorityOrderChecked++;
            checkNotBanned(s.titleSv());
            checkNotBanned(s.detailSv());
            checkNotBanned(s.impactSv());
        }
        // MINOR review fix (vacuous assertion): isGreaterThanOrEqualTo(0) can never fail for an int -
        // it asserted nothing. No hard floor is asserted here on purpose (see method javadoc: this
        // dataset may legitimately produce zero PRIORITY_ORDER suggestions - a greedy solve's weights
        // not matching a ladder permutation, or every unmet bucket wish there being hard-blocked, are
        // both honest zero-suggestion outcomes, not a test failure). The sweep over whatever WAS
        // generated is the actual test; logging the count keeps this visible instead of silent.
        log.info("noGeneratedPriorityOrderSentenceEverUsesTheBannedLexiconOnLarge120: priorityOrderChecked={}", priorityOrderChecked);
        // Sanity: this dataset does exercise real participants (never a silently-empty run).
        assertThat(participantProfileRepository.findByActivityPlanId(planId)).isNotEmpty();
        assertThat(response).isNotNull();
        ParticipantProfile anyParticipant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        assertThat(anyParticipant).isNotNull();
    }

    private void checkNotBanned(String text) {
        if (text == null) {
            return;
        }
        for (String banned : CausalNarrator.BANNED_LEXICON) {
            assertThat(text.toLowerCase()).doesNotContain(banned.toLowerCase());
        }
    }
}
