package se.klubb.groupplanner.explain;

import static ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy.FETCH_ALL;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * M-E0 spike (v0.6.0, gates the "weight sensitivity"/explainability feature): mechanically proves or
 * disproves the properties an upcoming feature needs to be true - {@code
 * backend/docs/explain-linearity-spike.md} carries the write-up, verdicts, and design consequence.
 * FORKED from {@link se.klubb.groupplanner.solver.WeightOverrideFlipTest} (copied, not modified) -
 * this class reuses that fixture's two-group 18.00/19.30 friend-wish-vs-size layout almost verbatim,
 * extended with a level imbalance (for {@code levelBalance}/{@code groupOrderByLevel}) and a time
 * preference (for {@code timePreferenceSoft}) on participant A so a single A-move exercises five
 * constraints at once.
 *
 * <p><b>The four core properties under test</b> (one {@code @Test} method each, in source order) -
 * see each method's javadoc for the mechanically-observed verdict, not an assumed one:
 *
 * <ol>
 *   <li>(a) {@link #overridesHonoredPerAnalyzeCall_onSameSolutionInstance_noFactRebuildRequired()}
 *   <li>(b) {@link #moveDeltaIsExactlyLinearInConstraintWeights_acrossSevenWeightVectors()}
 *   <li>(c) {@link #zeroWeightConstraintIsOmittedFromScoreAnalysis_entirely_notJustZeroed()}
 *   <li>(d) {@link #diffSubtractsConstraintWeightJustLikeScore_soDiffWeightIsUselessAcrossSameWeightOperands()}
 * </ol>
 *
 * <p><b>Coverage added during the M-E0 review pass</b> (closing oracle/coverage holes a two-reviewer
 * pass found in (b) - see each method's javadoc for what specifically it closes):
 *
 * <ul>
 *   <li>{@link #unassignedPlayerMediumDeltaIsAlsoExactlyLinear_moveToWaitlist()} - MEDIUM level
 *       coverage via {@code unassignedPlayer} (a probe (b) itself never exercises: the move-to-G2
 *       fixture never unassigns anyone).
 *   <li>{@link #groupMaxSizeHardDeltaIsAlsoExactlyLinear_overfilledGroup()} - HARD level coverage via
 *       {@code groupMaxSizeHard} (same reason: the move-to-G2 fixture never overflows a group).
 *   <li>{@link #coachPreferenceSoftRewardWeightStaysPositiveNotNegated_zeroMatchEntry()} - a
 *       COMMITTED assertion for the REWARD sign-convention claim in the class javadoc below, which
 *       previously rested only on a deleted scratch test.
 * </ul>
 *
 * <p><b>A universal empirical finding that shapes every test below</b>: {@link
 * ConstraintAnalysis#weight()} reports the SIGNED per-match multiplier actually used in scoring, not
 * the positive magnitude passed to {@code ConstraintWeightOverrides.of(...)}/{@code ofSoft(...)} -
 * e.g. overriding {@code sameGroupSoft} (a {@code .penalize(...)} constraint) to {@code ofSoft(777)}
 * makes {@code weight()} report {@code -777soft}, because that IS the number Timefold multiplies a
 * match's {@code matchWeight} by to get its score contribution. A {@code .reward(...)} constraint
 * (e.g. {@code coachPreferenceSoft}) reports the override's sign UNCHANGED (positive). This was
 * discovered mechanically (see the debug dumps this file's derivation logic is built from) and is
 * NOT documented behavior this spike assumed going in - it is why every "weight_k"/"units_k" in this
 * file is deliberately kept in whatever sign Timefold itself reports, rather than re-normalized to
 * the override's positive input convention. {@link #SIGN_BY_KEY} is the executable form of that same
 * finding, used to predict a constraint's applied weight WITHOUT ever reading it back off Timefold's
 * own analysis (see (b)'s javadoc, "closing the oracle hole").
 *
 * <p><b>Not covered by this spike</b>: every constraint in {@code GroupPlanConstraintProvider} is
 * either {@code .penalize(...)} or {@code .reward(...)} - {@code .impact(...)} (Timefold's third,
 * MIXED-sign case, where a single constraint can both penalize and reward depending on the match) is
 * unused in this codebase and therefore mechanically unverified here. A future {@code .impact(...)}
 * constraint must NOT silently inherit this spike's "sign is a static per-constraint fact" verdict -
 * it would need its own probe.
 */
class WeightSensitivityLinearityTest {

    private static final long PARTICIPANT_A = 5L;
    private static final long PARTICIPANT_B = 6L;

    // ─────────────────────────────────────────────────────────────────── (a) overrides + rebuild

    /**
     * Property (a): does a solution instance need any kind of "fact rebuild" between two {@code
     * analyze()} calls that use DIFFERENT {@code ConstraintWeightOverrides} - i.e. is {@code
     * SolutionManager.analyze} sensitive to overrides mutated on the object graph IN PLACE, the same
     * way {@link MoveProbe} already relies on it being sensitive to a {@code PlayerAssignment
     * .setGroup} mutation (see that class's javadoc: "a pure read of the current object graph ...
     * recomputes the whole score fresh every call, never incrementally")?
     *
     * <p><b>Verdict: YES, honored every call, NO rebuild of any kind required or possible</b> -
     * mechanically proven here by calling {@code analyze()} THREE times on the exact same {@code
     * SolutionManager} + {@code GroupPlanSolution} instance, with nothing between calls except a
     * {@code solution.setConstraintWeightOverrides(...)} setter call: no new {@code
     * GroupPlanSolution}, no new {@code SolverFactory}/{@code SolutionManager}, no explicit
     * "reconfigure"/"rebuild" API of any kind (none exists on {@code SolutionManager} to call even if
     * we wanted to - {@code ConstraintWeightOverrides} is, per its own design, a solve-time value read
     * fresh off the solution on every score computation, unlike the older {@code
     * @ConstraintConfiguration} mechanism it replaces). The third call reverts to the first call's
     * weight to rule out a one-way "warm-up" artifact.
     */
    @Test
    void overridesHonoredPerAnalyzeCall_onSameSolutionInstance_noFactRebuildRequired() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildSplit(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(80))));

        ScoreAnalysis<HardMediumSoftLongScore> underW1 = manager.analyze(solution, FETCH_ALL);
        assertThat(scoreOf(underW1, ConstraintKeys.SAME_GROUP_SOFT)).isEqualTo(HardMediumSoftLongScore.ofSoft(-80));

        // Nothing but a setter call on the SAME solution instance - no rebuild of any kind.
        solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(999))));
        ScoreAnalysis<HardMediumSoftLongScore> underW2 = manager.analyze(solution, FETCH_ALL);
        assertThat(scoreOf(underW2, ConstraintKeys.SAME_GROUP_SOFT)).isEqualTo(HardMediumSoftLongScore.ofSoft(-999));

        // Revert to W1 on the SAME instance/manager - rules out a one-way "first call wins" artifact.
        solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(80))));
        ScoreAnalysis<HardMediumSoftLongScore> underW1Again = manager.analyze(solution, FETCH_ALL);
        assertThat(scoreOf(underW1Again, ConstraintKeys.SAME_GROUP_SOFT)).isEqualTo(HardMediumSoftLongScore.ofSoft(-80));
    }

    // ─────────────────────────────────────────────────────────────────── (b) exact linearity

    /**
     * Property (b), the spike's central claim: for a FIXED single-player move (A: G1 -&gt; G2, {@code
     * setGroup} exactly as {@link MoveProbe} performs it), is {@code Δscore_k} for every touched
     * constraint {@code k} EXACTLY {@code weight_k x units_k} for an integer {@code units_k} that does
     * NOT depend on the weight - i.e. is {@code Δscore(w') = Σ units_k · w'_k} for ANY weight vector
     * {@code w'}, not just the one the units were derived under, including one that RECLASSIFIES a
     * constraint's score level (HARD&lt;-&gt;SOFT, which this app allows per plan - {@code
     * ConstraintWeightService.validateReclassification})?
     *
     * <p><b>Verdict: YES, exactly, bit-for-bit, at any level</b>. This holds by construction of
     * Timefold's constraint-streams scoring model - {@code .penalize(weight, matchWeightFn)}/{@code
     * .reward(weight, matchWeightFn)} always compute a match's score contribution as {@code weight x
     * matchWeightFn(...)}, and {@code matchWeightFn} has no access to {@code weight} (or its level) -
     * so for a FIXED pair of solution states (baseline/moved), {@code Δscore_k} is an exactly-linear
     * function of {@code weight_k} with no other free variable, REGARDLESS of which score level that
     * weight lives at.
     *
     * <p>Step 1 below derives {@code units_k} from the move's diff under DEFAULT weights (asserting
     * the division has zero remainder, and that each constraint's delta lives entirely in the ONE
     * score level - hard/medium/soft - its DEFAULT weight is declared at, per {@code
     * GroupPlanConstraintProvider}'s one-level-per-constraint design). Step 2 then, for SEVEN fixed
     * weight vectors (varying {@code groupSizeTarget}/{@code sameGroupSoft}/{@code
     * timePreferenceSoft}/{@code levelBalance}, including one all-{@code 1} vector, one
     * all-{@code 10000} vector, one that reclassifies {@code sameGroupSoft} from SOFT to HARD, and one
     * that zeroes out {@code timePreferenceSoft}), predicts {@code Δscore(w')} and asserts it EXACTLY
     * against actually re-analyzing baseline &amp; moved states under that vector - both in aggregate
     * ({@code diffV.score()}) and per touched constraint ({@code analysisOf(diffV, key).score()}, the
     * exact claim a future per-constraint "what would this weight change do" UI makes).
     *
     * <p><b>Closing the oracle hole (review fix)</b>: an earlier version of this test predicted each
     * vector's applied weight by reading it back off the freshly-analyzed {@code baseV} itself ({@code
     * analysisOf(baseV, key).weight()}) - so if Timefold silently ignored an override, the prediction
     * and the "actual" would both be derived from that same (buggy) call and the test would still
     * pass. {@link #expectedAppliedWeight} now predicts each vector's applied weight ENTIRELY from the
     * vector's own input (the positive override magnitude, the sign rule in {@link #SIGN_BY_KEY}, and
     * - critically for reclassification - the LEVEL the vector's OWN weight is declared at, never a
     * level pinned at derivation time), with zero dependency on any Timefold analysis call.
     * {@link #predictAndAssertPerConstraint} then separately asserts {@code analysisOf(baseV,
     * key).weight()} EQUALS that independently-predicted value - a real "did Timefold honor this"
     * check, no longer entangled with the prediction it is checking.
     *
     * <p><b>Level-free units (review fix)</b>: {@code units_k} is a property of match COUNTS under the
     * fixed baseline/moved states ({@code Δscore_k = weight_k x units_k}), which never depends on which
     * score level {@code weight_k} happens to live at. {@link ConstraintUnits} therefore no longer
     * pins a level for prediction purposes (its {@code level} field is kept only as the DEFAULT
     * derivation's own sanity-check bookkeeping) - {@link #predictAndAssertPerConstraint} reads the
     * level fresh from each TARGET vector's own weight every time, which is what makes vector 6 below
     * (a HARD&lt;-&gt;SOFT reclassification) predict correctly: a level-pinned dot product would sum
     * that vector's {@code sameGroupSoft} contribution into the SOFT component (its level at
     * derivation time) instead of HARD (its level under vector 6) - exactly the user-facing lie this
     * spike exists to rule out before a UI is built on top of it.
     *
     * <p><b>Zero-weight vector (review fix)</b>: vector 7 zeroes {@code timePreferenceSoft}. Per
     * property (c), a zero-weight constraint's {@code ConstraintAnalysis} entry is OMITTED ENTIRELY,
     * not zeroed-but-present - so {@link #predictAndAssertPerConstraint} special-cases a zero target
     * weight BEFORE ever calling {@link #analysisOf}, asserting the entry's absence (unitsKnown=false,
     * "no data") instead of letting {@code analysisOf}'s bare {@code AssertionError} fire.
     *
     * <p>The move touches five constraints at once by construction (see class javadoc): {@code
     * groupSizeTarget} (both groups' size deviation flips from 1 to 0 - a soft IMPROVEMENT despite the
     * move being an otherwise-losing trade), {@code sameGroupSoft} (the A/B wish breaks), {@code
     * levelBalance} (A's level is skewed relative to the group means), {@code timePreferenceSoft} (A
     * prefers G1's time slot), and {@code groupOrderByLevel} (the move happens to invert G1/G2's
     * mean-level ordering) - {@code groupOrderByLevel} is left UNVARIED across all 7 vectors (never
     * given a map entry, so it stays at its code-literal default weight every time), so it also proves
     * units for a constraint whose weight never changes still combine correctly with units for ones
     * that do.
     */
    @Test
    void moveDeltaIsExactlyLinearInConstraintWeights_acrossSevenWeightVectors() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildUnited(ConstraintWeightOverrides.none());
        Group g1 = groupOf(solution, 1L);
        Group g2 = groupOf(solution, 2L);
        PlayerAssignment target = playerOf(solution, PARTICIPANT_A);

        // --- Step 1: derive units_k under DEFAULT (code-literal) weights ---
        ScoreAnalysis<HardMediumSoftLongScore> baseDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g2);
        ScoreAnalysis<HardMediumSoftLongScore> movedDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g1); // restore before the next analyze() call - MoveProbe's exact pattern.
        ScoreAnalysis<HardMediumSoftLongScore> diffDefault = movedDefault.diff(baseDefault);

        // Weight is deliberately sourced from `baseDefault` (a RAW analysis), NOT `diffDefault` - see
        // this method's javadoc "load-bearing correction" / property (d) below.
        Map<String, ConstraintUnits> unitsByKey = deriveUnits(diffDefault, baseDefault);

        // diff() only lists constraints with an actual change (see (d)'s javadoc/dumps) - so its 5
        // entries ARE exactly the touched set; no separate "everything else is zero" loop is needed.
        assertThat(unitsByKey).containsOnlyKeys(
                ConstraintKeys.GROUP_SIZE_TARGET,
                ConstraintKeys.SAME_GROUP_SOFT,
                ConstraintKeys.TIME_PREFERENCE_SOFT,
                ConstraintKeys.LEVEL_BALANCE,
                ConstraintKeys.GROUP_ORDER_BY_LEVEL);

        // Sanity anchors (signs follow ConstraintAnalysis.weight()'s convention - see class javadoc):
        // groupSizeTarget's weight is reported NEGATIVE (-50, a .penalize constraint) and its delta is
        // POSITIVE (+100, an improvement), so units_k = 100 / -50 = -2, etc.
        assertThat(unitsByKey.get(ConstraintKeys.GROUP_SIZE_TARGET).units()).isEqualTo(-2L);
        assertThat(unitsByKey.get(ConstraintKeys.SAME_GROUP_SOFT).units()).isEqualTo(1L);
        assertThat(unitsByKey.get(ConstraintKeys.TIME_PREFERENCE_SOFT).units()).isEqualTo(1L);
        assertThat(unitsByKey.get(ConstraintKeys.LEVEL_BALANCE).units()).isEqualTo(132L);
        assertThat(unitsByKey.get(ConstraintKeys.GROUP_ORDER_BY_LEVEL).units()).isEqualTo(134L);
        assertThat(diffDefault.score()).isEqualTo(HardMediumSoftLongScore.of(0, 0, -19_920));

        // --- Step 2: predict Δscore for seven fixed weight vectors, verify against a real re-analyze ---
        List<Map<String, HardMediumSoftLongScore>> vectors = List.of(
                // All-MIN (WeightLimits.MIN_WEIGHT = 1).
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(1),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(1),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(1),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(1)),
                // All-MAX (WeightLimits.MAX_WEIGHT = 10 000).
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(10_000),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(10_000),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(10_000),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(10_000)),
                // Mixed, moderate.
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(7),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(123),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(45),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(9)),
                // Mixed, one dominant weight.
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(999),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(3),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(6_000),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(17)),
                // Mixed, one left at its code-default value on purpose (groupSizeTarget=50).
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(50),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(1),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(10_000),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(250)),
                // Vector 6 (review fix, FIX 2): RECLASSIFIES sameGroupSoft SOFT -> HARD, exactly the
                // per-plan reclassification ConstraintWeightService.validateReclassification allows
                // (and SolverInputAssembler/conflictsAsWarnings already exercises in production for
                // the savedPlan* constraints). units_k for sameGroupSoft was derived under its DEFAULT
                // SOFT weight but must combine correctly here too - a level-pinned predictor would
                // wrongly place its contribution in the SOFT component instead of HARD.
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(20),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofHard(3),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(15),
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(8)),
                // Vector 7 (review fix, FIX 6): ZEROES timePreferenceSoft. Per property (c) its
                // ScoreAnalysis entry must be entirely ABSENT, not present-with-zero-score - exercised
                // via predictAndAssertPerConstraint's explicit "no data" branch.
                Map.of(
                        ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(33),
                        ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(200),
                        ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ZERO,
                        ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(5)));

        for (Map<String, HardMediumSoftLongScore> vector : vectors) {
            solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(vector));

            ScoreAnalysis<HardMediumSoftLongScore> baseV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(g2);
            ScoreAnalysis<HardMediumSoftLongScore> movedV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(g1);
            ScoreAnalysis<HardMediumSoftLongScore> diffV = movedV.diff(baseV);

            HardMediumSoftLongScore predicted = predictAndAssertPerConstraint(unitsByKey, vector, baseV, diffV);
            assertThat(diffV.score()).as("weight vector " + vector).isEqualTo(predicted);
        }
    }

    /**
     * Every constraint key's DEFAULT (code-literal) weight, as declared in {@code
     * GroupPlanConstraintProvider} - a magnitude only (always POSITIVE), independent of sign. Used as
     * the fallback for any touched key a weight vector deliberately omits (left "unvaried" - see (b)'s
     * javadoc), and as the base for {@link #scaledVector}. Deliberately hand-transcribed from the
     * constraint provider's source rather than read off a "defaults" {@code ScoreAnalysis} call, so
     * this map cannot share any circularity with the very Timefold calls the tests around it verify -
     * it rots LOUDLY (assertion failure, not silent skew) if a provider default ever changes without
     * updating this map.
     */
    private static final Map<String, HardMediumSoftLongScore> CODE_DEFAULT_WEIGHT = Map.ofEntries(
            Map.entry(ConstraintKeys.TRAINING_BLOCK_CAPACITY, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.GROUP_MAX_SIZE_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.TIME_AVAILABILITY_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.SAME_GROUP_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.DIFFERENT_GROUP_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_NO_OVERLAP, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.PLAYER_NO_OVERLAP, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_AVAILABILITY_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_REQUIREMENT_HARD, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_MAX_GROUPS, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_WISH_REQUIRED, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.COACH_WISH_FORBIDDEN, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.SAVED_PLAN_PERSON_BLOCKED, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.SAVED_PLAN_COACH_BLOCKED, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.SAVED_PLAN_COURT_BLOCKED, HardMediumSoftLongScore.ofHard(1)),
            Map.entry(ConstraintKeys.UNASSIGNED_PLAYER, HardMediumSoftLongScore.ofMedium(100)),
            Map.entry(ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.GROUP_SIZE_TARGET_EMPTY, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.GROUP_MIN_SIZE_SOFT, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.GROUP_MIN_SIZE_EMPTY, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(100)),
            Map.entry(ConstraintKeys.GROUP_ORDER_BY_LEVEL, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, HardMediumSoftLongScore.ofSoft(30)),
            Map.entry(ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(40)),
            Map.entry(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(80)),
            Map.entry(ConstraintKeys.DIFFERENT_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(60)),
            Map.entry(ConstraintKeys.COACH_LEVEL_FIT, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.COACH_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.LATE_TIME_TOP_GROUPS, HardMediumSoftLongScore.ofSoft(30)),
            Map.entry(ConstraintKeys.LATE_TIME_BOTTOM_GROUPS, HardMediumSoftLongScore.ofSoft(30)),
            Map.entry(ConstraintKeys.COACH_PREFERRED_TIME_SLOT, HardMediumSoftLongScore.ofSoft(20)),
            Map.entry(ConstraintKeys.COACH_UNKNOWN_TIME_SLOT, HardMediumSoftLongScore.ofSoft(20)));

    /** Whether a constraint key is coded as {@code .penalize(...)} or {@code .reward(...)} in {@code
     * GroupPlanConstraintProvider} - the executable form of the class javadoc's sign-convention
     * finding, used to predict {@code ConstraintAnalysis.weight()}'s sign WITHOUT ever reading it back
     * off a Timefold analysis call (see (b)'s javadoc, "closing the oracle hole"). */
    private enum Sign {
        PENALIZE,
        REWARD
    }

    private static final Map<String, Sign> SIGN_BY_KEY = Map.ofEntries(
            Map.entry(ConstraintKeys.TRAINING_BLOCK_CAPACITY, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_MAX_SIZE_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.TIME_AVAILABILITY_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.SAME_GROUP_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.DIFFERENT_GROUP_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_NO_OVERLAP, Sign.PENALIZE),
            Map.entry(ConstraintKeys.PLAYER_NO_OVERLAP, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_AVAILABILITY_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_REQUIREMENT_HARD, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_MAX_GROUPS, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_WISH_REQUIRED, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_WISH_FORBIDDEN, Sign.PENALIZE),
            Map.entry(ConstraintKeys.SAVED_PLAN_PERSON_BLOCKED, Sign.PENALIZE),
            Map.entry(ConstraintKeys.SAVED_PLAN_COACH_BLOCKED, Sign.PENALIZE),
            Map.entry(ConstraintKeys.SAVED_PLAN_COURT_BLOCKED, Sign.PENALIZE),
            Map.entry(ConstraintKeys.UNASSIGNED_PLAYER, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_SIZE_TARGET, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_SIZE_TARGET_EMPTY, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_MIN_SIZE_SOFT, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_MIN_SIZE_EMPTY, Sign.PENALIZE),
            Map.entry(ConstraintKeys.LEVEL_BALANCE, Sign.PENALIZE),
            Map.entry(ConstraintKeys.GROUP_ORDER_BY_LEVEL, Sign.PENALIZE),
            Map.entry(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, Sign.PENALIZE),
            Map.entry(ConstraintKeys.TIME_PREFERENCE_SOFT, Sign.PENALIZE),
            Map.entry(ConstraintKeys.SAME_GROUP_SOFT, Sign.PENALIZE),
            Map.entry(ConstraintKeys.DIFFERENT_GROUP_SOFT, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_LEVEL_FIT, Sign.PENALIZE),
            Map.entry(ConstraintKeys.COACH_PREFERENCE_SOFT, Sign.REWARD),
            Map.entry(ConstraintKeys.LATE_TIME_TOP_GROUPS, Sign.PENALIZE),
            Map.entry(ConstraintKeys.LATE_TIME_BOTTOM_GROUPS, Sign.REWARD),
            Map.entry(ConstraintKeys.COACH_PREFERRED_TIME_SLOT, Sign.REWARD),
            Map.entry(ConstraintKeys.COACH_UNKNOWN_TIME_SLOT, Sign.PENALIZE));

    /**
     * Independently predicts the SIGNED, applied weight Timefold should report for {@code key} given
     * a POSITIVE magnitude score (either a vector's own override entry, or {@link #CODE_DEFAULT_WEIGHT}
     * when the vector omits that key) - using ONLY {@link #SIGN_BY_KEY} and the magnitude's OWN
     * declared level (never a level pinned elsewhere). Zero dependency on any {@code ScoreAnalysis}
     * call - see (b)'s javadoc "closing the oracle hole".
     */
    private static HardMediumSoftLongScore expectedAppliedWeight(String key, HardMediumSoftLongScore positiveMagnitude) {
        if (SIGN_BY_KEY.get(key) == Sign.REWARD) {
            return positiveMagnitude;
        }
        int level = soleNonZeroLevel(positiveMagnitude);
        return atLevel(level, -componentOf(positiveMagnitude, level));
    }

    private static HardMediumSoftLongScore atLevel(int level, long magnitude) {
        return switch (level) {
            case 0 -> HardMediumSoftLongScore.ofHard(magnitude);
            case 1 -> HardMediumSoftLongScore.ofMedium(magnitude);
            case 2 -> HardMediumSoftLongScore.ofSoft(magnitude);
            default -> throw new IllegalArgumentException("level must be 0/1/2, got " + level);
        };
    }

    /**
     * Builds a weight-vector map that scales every constraint in {@code keys} to {@code factor} times
     * its {@link #CODE_DEFAULT_WEIGHT} magnitude, AT ITS DEFAULT LEVEL (no reclassification - that is
     * exercised separately, by (b)'s vector 6). Used by the MEDIUM/HARD coverage probes below so their
     * vectors do not need hand-derived magic numbers - only the touched-key SET (discovered
     * mechanically via {@link #deriveUnits}) needs to be known ahead of time.
     */
    private static Map<String, HardMediumSoftLongScore> scaledVector(Set<String> keys, long factor) {
        Map<String, HardMediumSoftLongScore> vector = new HashMap<>();
        for (String key : keys) {
            HardMediumSoftLongScore def = CODE_DEFAULT_WEIGHT.get(key);
            assertThat(def).as("no CODE_DEFAULT_WEIGHT registered for touched key " + key).isNotNull();
            int level = soleNonZeroLevel(def);
            vector.put(key, atLevel(level, componentOf(def, level) * factor));
        }
        return vector;
    }

    /**
     * For every touched constraint in {@code unitsByKey}, predicts {@code Δscore_k} under {@code
     * vector} (independently of Timefold, per {@link #expectedAppliedWeight}), asserts it against a
     * REAL analysis in TWO independent ways - {@code analysisOf(baseV, key).weight()} (the "honoring"
     * check: did Timefold actually apply this override?) and {@code analysisOf(diffV, key).score()}
     * (the "per-constraint linearity" check - review fix, FIX 4: the exact claim a future
     * per-constraint UI makes, not just the summed total) - and returns the summed predicted total
     * score for the caller to assert against {@code diffV.score()} as well.
     *
     * <p>A touched key whose {@code vector} entry (or {@link #CODE_DEFAULT_WEIGHT} fallback) is
     * exactly {@link HardMediumSoftLongScore#ZERO} is handled as "no data" (review fix, FIX 6): per
     * property (c) a zero-weight constraint's {@code ScoreAnalysis} entry is OMITTED entirely, so this
     * asserts that absence directly rather than calling {@link #analysisOf}, whose bare {@code
     * AssertionError} is meant for an ACTUAL bug (a touched key mysteriously missing at a NONZERO
     * weight), not this expected, well-understood case.
     */
    private static HardMediumSoftLongScore predictAndAssertPerConstraint(
            Map<String, ConstraintUnits> unitsByKey,
            Map<String, HardMediumSoftLongScore> vector,
            ScoreAnalysis<HardMediumSoftLongScore> baseV,
            ScoreAnalysis<HardMediumSoftLongScore> diffV) {
        long hard = 0L;
        long medium = 0L;
        long soft = 0L;
        for (ConstraintUnits u : unitsByKey.values()) {
            String key = u.key();
            HardMediumSoftLongScore magnitude = vector.getOrDefault(key, CODE_DEFAULT_WEIGHT.get(key));
            assertThat(magnitude).as("no weight (override or default) resolvable for touched key " + key).isNotNull();

            if (HardMediumSoftLongScore.ZERO.equals(magnitude)) {
                boolean presentInBaseV = baseV.constraintMap().values().stream()
                        .anyMatch(ca -> key.equals(ca.constraintName()));
                assertThat(presentInBaseV)
                        .as(key + " is zero-weighted under this vector - its entry must be entirely ABSENT "
                                + "(unitsKnown=false, no data), not present-with-zero-score")
                        .isFalse();
                continue; // No data, no contribution - nothing further to predict or honor for this key.
            }

            HardMediumSoftLongScore expected = expectedAppliedWeight(key, magnitude);
            assertThat(analysisOf(baseV, key).weight())
                    .as(key + " applied weight under vector " + vector)
                    .isEqualTo(expected);

            int level = soleNonZeroLevel(expected);
            long contribution = u.units() * componentOf(expected, level);
            HardMediumSoftLongScore predictedForKey = atLevel(level, contribution);
            switch (level) {
                case 0 -> hard += contribution;
                case 1 -> medium += contribution;
                case 2 -> soft += contribution;
                default -> throw new IllegalStateException();
            }

            assertThat(analysisOf(diffV, key).score())
                    .as(key + " per-constraint diff score under vector " + vector)
                    .isEqualTo(predictedForKey);
        }
        return HardMediumSoftLongScore.of(hard, medium, soft);
    }

    /**
     * Derives {@code units_k = Δscore_k / weight_k} for every constraint present in {@code diff},
     * reading {@code weight_k} from {@code weightSource} (a RAW, non-diffed analysis computed under
     * the SAME weight vector as both operands of {@code diff} - see the caller's javadoc for why it
     * must not come from {@code diff} itself), and asserting (1) the constraint's weight there is
     * declared at exactly ONE score level - the invariant every constraint in {@code
     * GroupPlanConstraintProvider} is written to (never mixes {@code ofHard}/{@code ofMedium}/{@code
     * ofSoft} for the same {@code .asConstraint} key), (2) the delta lives entirely at that SAME level
     * (never bleeds into a level the constraint doesn't use - guaranteed by (1) plus how {@code
     * .penalize(weight, fn)} multiplies, but checked explicitly since the whole point of this spike is
     * to check rather than assume), and (3) the division has zero remainder (guaranteed algebraically:
     * score = weight x matchWeightSum where matchWeightSum does not depend on weight, so Δscore =
     * weight x Δ(matchWeightSum), an exact multiple - checked explicitly, again, rather than assumed).
     */
    private static Map<String, ConstraintUnits> deriveUnits(
            ScoreAnalysis<HardMediumSoftLongScore> diff, ScoreAnalysis<HardMediumSoftLongScore> weightSource) {
        Map<String, ConstraintUnits> result = new HashMap<>();
        for (ConstraintAnalysis<HardMediumSoftLongScore> diffed : diff.constraintAnalyses()) {
            String key = diffed.constraintName();
            HardMediumSoftLongScore weight = analysisOf(weightSource, key).weight();
            int level = soleNonZeroLevel(weight);
            assertThat(level)
                    .as(key + " weight must be declared at exactly one score level, got " + weight)
                    .isGreaterThanOrEqualTo(0);
            long weightScalar = componentOf(weight, level);
            for (int other = 0; other < 3; other++) {
                if (other != level) {
                    assertThat(componentOf(diffed.score(), other))
                            .as(key + " delta bled into an unrelated score level")
                            .isZero();
                }
            }
            long deltaScalar = componentOf(diffed.score(), level);
            assertThat(deltaScalar % weightScalar).as(key + " Δscore/weight must divide exactly").isZero();
            long units = deltaScalar / weightScalar;
            result.put(key, new ConstraintUnits(key, level, weightScalar, deltaScalar, units));
        }
        return result;
    }

    /** 0=hard, 1=medium, 2=soft; -1 if the score is entirely zero (never happens for a code-literal
     * default weight in this constraint provider - every constraint has a nonzero literal weight -
     * but checked rather than assumed). */
    private static int soleNonZeroLevel(HardMediumSoftLongScore s) {
        int found = -1;
        for (int level = 0; level < 3; level++) {
            if (componentOf(s, level) != 0L) {
                if (found >= 0) {
                    throw new AssertionError("score has more than one nonzero level: " + s);
                }
                found = level;
            }
        }
        return found;
    }

    private static long componentOf(HardMediumSoftLongScore s, int level) {
        return switch (level) {
            case 0 -> s.hardScore();
            case 1 -> s.mediumScore();
            case 2 -> s.softScore();
            default -> throw new IllegalArgumentException("level must be 0/1/2, got " + level);
        };
    }

    /** {@code level}/{@code weightScalar} record the DEFAULT derivation's own bookkeeping only (used
     * by {@link #deriveUnits}'s internal sanity checks) - {@code units}, the level-free scalar that
     * actually generalizes across weight vectors (including a reclassified one), is the field {@link
     * #predictAndAssertPerConstraint} uses; it deliberately does NOT read {@code level} back out for
     * prediction purposes (see (b)'s javadoc, "level-free units"). */
    private record ConstraintUnits(String key, int level, long weightScalar, long deltaScalar, long units) {
    }

    // ─────────────────────────────────────────────────────── (b) coverage: MEDIUM level

    /**
     * Coverage extension of (b) (review fix, FIX 3a): the primary A: G1-&gt;G2 move above never
     * touches {@code unassignedPlayer} (MEDIUM, the reserved waitlist penalty, {@code
     * PlayerAssignment::getPriority} as its per-priority {@code matchWeightFn}) because nobody ever
     * becomes unassigned in that fixture. This probe moves A to the waitlist instead ({@code
     * setGroup(null)}, {@code allowsUnassigned=true} on {@code PlayerAssignment.group}) and reuses the
     * exact same derive-then-predict-then-verify machinery ({@link #deriveUnits}/{@link
     * #predictAndAssertPerConstraint}) to prove the same exact-linearity claim holds for a REAL MEDIUM
     * delta, not just HARD/SOFT.
     *
     * <p>The touched set is discovered mechanically (not hand-enumerated) since a player leaving a
     * group via {@code forEach(PlayerAssignment.class)} - which, unlike {@code
     * forEachIncludingUnassigned}, silently excludes an unassigned entity - moves more than just {@code
     * unassignedPlayer}: {@code groupSizeTarget}/{@code levelBalance}/{@code groupOrderByLevel} also
     * shift since A drops out of G1's {@code groupBy} entirely. Only {@code unassignedPlayer}'s
     * presence is asserted explicitly; the rest are along for the ride and validated the same way
     * regardless of which keys they turn out to be.
     */
    @Test
    void unassignedPlayerMediumDeltaIsAlsoExactlyLinear_moveToWaitlist() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildUnited(ConstraintWeightOverrides.none());
        Group g1 = groupOf(solution, 1L);
        PlayerAssignment target = playerOf(solution, PARTICIPANT_A);

        ScoreAnalysis<HardMediumSoftLongScore> baseDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(null);
        ScoreAnalysis<HardMediumSoftLongScore> movedDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g1);
        ScoreAnalysis<HardMediumSoftLongScore> diffDefault = movedDefault.diff(baseDefault);

        Map<String, ConstraintUnits> unitsByKey = deriveUnits(diffDefault, baseDefault);
        assertThat(unitsByKey)
                .as("moving a player to the waitlist must produce a real MEDIUM delta via unassignedPlayer")
                .containsKey(ConstraintKeys.UNASSIGNED_PLAYER);

        List<Map<String, HardMediumSoftLongScore>> vectors = List.of(
                scaledVector(unitsByKey.keySet(), 1),
                scaledVector(unitsByKey.keySet(), 5),
                scaledVector(unitsByKey.keySet(), 137));

        for (Map<String, HardMediumSoftLongScore> vector : vectors) {
            solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(vector));
            ScoreAnalysis<HardMediumSoftLongScore> baseV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(null);
            ScoreAnalysis<HardMediumSoftLongScore> movedV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(g1);
            ScoreAnalysis<HardMediumSoftLongScore> diffV = movedV.diff(baseV);

            HardMediumSoftLongScore predicted = predictAndAssertPerConstraint(unitsByKey, vector, baseV, diffV);
            assertThat(diffV.score()).as("weight vector " + vector).isEqualTo(predicted);
        }
    }

    // ─────────────────────────────────────────────────────── (b) coverage: HARD level

    /**
     * Coverage extension of (b) (review fix, FIX 3b): the primary A: G1-&gt;G2 move above never
     * overflows a group (both groups stay at or under {@code maxSize}), so it never touches {@code
     * groupMaxSizeHard}. This probe instead moves E (pinned into G2 by {@link #buildUnited}, but
     * {@code @PlanningPin} only blocks the SOLVER's own move selection - direct {@code setGroup}
     * mutation on the object graph is exactly the pattern {@link MoveProbe}/every other test in this
     * file already relies on) into G1, which sits at its {@code maxSize=4} in the baseline (C, D, A, B)
     * and overflows to 5 - a real HARD delta - reusing the same derive-then-predict-then-verify
     * machinery as every other test in this file.
     */
    @Test
    void groupMaxSizeHardDeltaIsAlsoExactlyLinear_overfilledGroup() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildUnited(ConstraintWeightOverrides.none());
        Group g1 = groupOf(solution, 1L);
        Group g2 = groupOf(solution, 2L);
        PlayerAssignment target = playerOf(solution, 3L); // "E" - pinned into g2 by buildUnited.

        ScoreAnalysis<HardMediumSoftLongScore> baseDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g1); // G1 is C,D,A,B (already at maxSize=4) - this overflows it to 5.
        ScoreAnalysis<HardMediumSoftLongScore> movedDefault = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g2);
        ScoreAnalysis<HardMediumSoftLongScore> diffDefault = movedDefault.diff(baseDefault);

        Map<String, ConstraintUnits> unitsByKey = deriveUnits(diffDefault, baseDefault);
        assertThat(unitsByKey)
                .as("overfilling a group past maxSize must produce a real HARD delta via groupMaxSizeHard")
                .containsKey(ConstraintKeys.GROUP_MAX_SIZE_HARD);

        List<Map<String, HardMediumSoftLongScore>> vectors = List.of(
                scaledVector(unitsByKey.keySet(), 1),
                scaledVector(unitsByKey.keySet(), 5),
                scaledVector(unitsByKey.keySet(), 137));

        for (Map<String, HardMediumSoftLongScore> vector : vectors) {
            solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(vector));
            ScoreAnalysis<HardMediumSoftLongScore> baseV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(g1);
            ScoreAnalysis<HardMediumSoftLongScore> movedV = manager.analyze(solution, FETCH_ALL);
            target.setGroup(g2);
            ScoreAnalysis<HardMediumSoftLongScore> diffV = movedV.diff(baseV);

            HardMediumSoftLongScore predicted = predictAndAssertPerConstraint(unitsByKey, vector, baseV, diffV);
            assertThat(diffV.score()).as("weight vector " + vector).isEqualTo(predicted);
        }
    }

    // ─────────────────────────────────────────────────────── (b) coverage: REWARD sign, committed

    /**
     * Coverage extension of (b) (review fix, FIX 3c): the class javadoc's REWARD sign-convention claim
     * ("a {@code .reward(...)} constraint reports the override's sign UNCHANGED, positive") previously
     * rested only on a deleted scratch test - no committed assertion backed it. This fixture has no
     * {@code CoachWish}/{@code CoachSlot}/{@code CoachFact} at all, so {@code coachPreferenceSoft} (a
     * {@code .reward(...)} constraint) NEVER matches here; per property (c) below, a NONZERO-weight
     * constraint's entry is still present in {@code ScoreAnalysis} at {@code FETCH_ALL} even with
     * {@code matchCount=0} (this is exactly why the (c) baseline count is 33, not fewer - every
     * constraint the provider defines is enumerated, matches or not), so {@code weight()} is directly
     * observable without standing up any real coach fixture data.
     */
    @Test
    void coachPreferenceSoftRewardWeightStaysPositiveNotNegated_zeroMatchEntry() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildUnited(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.COACH_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(777))));

        ScoreAnalysis<HardMediumSoftLongScore> analysis = manager.analyze(solution, FETCH_ALL);
        ConstraintAnalysis<HardMediumSoftLongScore> ca = analysisOf(analysis, ConstraintKeys.COACH_PREFERENCE_SOFT);

        assertThat(ca.matchCount()).isZero();
        assertThat(ca.matches()).isEmpty();
        assertThat(ca.weight())
                .as("a .reward(...) constraint reports the override's sign UNCHANGED (positive), unlike .penalize(...)")
                .isEqualTo(HardMediumSoftLongScore.ofSoft(777));
    }

    // ─────────────────────────────────────────────────────────────────── (c) zero-weight semantics

    /**
     * Property (c): the API floor {@code WeightLimits.MIN_WEIGHT = 1} only bounds the DB-backed
     * {@code ConstraintWeightService}/{@code FieldDefinitionValidator} WRITE paths (an ENABLED
     * constraint/field's user-settable weight) - nothing in {@code ConstraintWeightOverrides} itself
     * rejects a weight of zero. And {@code SolverInputAssembler.buildConstraintWeightOverrides} (~line
     * 623/647) proves DISABLED constraints reach the solver via exactly this mechanism: {@code
     * def.enabled() ? def.defaultWeight() : 0} - an EXPLICIT zero-weight entry in the overrides map,
     * never an omitted key and never a constraint filtered out of {@code
     * GroupPlanConstraintProvider#defineConstraints}'s array. So "disabled" and "weight zero" are the
     * SAME mechanism in this codebase, not two different ones.
     *
     * <p><b>Verdict: a zero-weight constraint's {@code ConstraintAnalysis} entry is OMITTED ENTIRELY
     * from {@code ScoreAnalysis.constraintMap()}</b> - not merely present-but-zeroed. Empirically: the
     * SAME split state (A/B in different groups, the {@code WANT_SAME} wish broken) analyzed under a
     * zero {@code sameGroupSoft} weight has NO {@code sameGroupSoft} key in {@code constraintMap()} at
     * all (32 entries instead of the normal 33 - pinned explicitly below, per review fix FIX 5, so
     * this test fails LOUDLY rather than silently drifting if a constraint is ever added to or removed
     * from {@code GroupPlanConstraintProvider#defineConstraints} without updating both counts here),
     * whereas analyzed under ANY nonzero weight it has one entry with {@code matchCount=1}. So
     * Timefold does not just zero the score of a disabled constraint's matches - it skips reporting
     * them (and, per the mechanism above, most likely skips EVALUATING them too, though this test can
     * only observe the reporting side).
     *
     * <p><b>Consequence for a future {@code unitsKnown=false} fallback</b>: a disabled/zero-weight
     * constraint gives the explainability feature NOTHING to show - not a match list, not a "would
     * affect these N players" hint, nothing - because there is no {@code ConstraintAnalysis} entry to
     * read from. {@code unitsKnown=false} must mean "no information available for this constraint",
     * not "information available but not proportional".
     */
    @Test
    void zeroWeightConstraintIsOmittedFromScoreAnalysis_entirely_notJustZeroed() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildSplit(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ZERO)));

        // ConstraintWeightOverrides.of() itself does not reject a zero-valued Score - the API floor
        // (WeightLimits.MIN_WEIGHT=1) is purely an application-layer write-path guardrail.
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.SAME_GROUP_SOFT))
                .isEqualTo(HardMediumSoftLongScore.ZERO);

        ScoreAnalysis<HardMediumSoftLongScore> zeroWeightAnalysis = manager.analyze(solution, FETCH_ALL);
        boolean presentAtZeroWeight = zeroWeightAnalysis.constraintMap().values().stream()
                .anyMatch(ca -> ConstraintKeys.SAME_GROUP_SOFT.equals(ca.constraintName()));
        assertThat(presentAtZeroWeight)
                .as("a zero-weight constraint's entry must be entirely absent from ScoreAnalysis")
                .isFalse();
        // Review fix, FIX 5: pinned entry count - GroupPlanConstraintProvider#defineConstraints's
        // array has exactly 33 entries today; one (sameGroupSoft) is zero-weighted here, so 32 remain.
        // Intentionally rots LOUDLY (this assertion fails) if a constraint is added/removed - update
        // this count AND the 33 below together when that happens.
        assertThat(zeroWeightAnalysis.constraintMap()).hasSize(32);

        // Control: the EXACT same split state, analyzed under sameGroupSoft's normal nonzero default
        // weight, DOES have the entry, with its one broken-wish match.
        GroupPlanSolution nonZeroWeightSolution = buildSplit(ConstraintWeightOverrides.none());
        ScoreAnalysis<HardMediumSoftLongScore> nonZeroWeightAnalysis = manager.analyze(nonZeroWeightSolution, FETCH_ALL);
        ConstraintAnalysis<HardMediumSoftLongScore> nonZeroCa =
                analysisOf(nonZeroWeightAnalysis, ConstraintKeys.SAME_GROUP_SOFT);
        assertThat(nonZeroCa.matchCount()).isEqualTo(1);
        assertThat(nonZeroCa.matches()).hasSize(1);
        assertThat(nonZeroCa.score()).isEqualTo(HardMediumSoftLongScore.ofSoft(-80));
        // Review fix, FIX 5: the full baseline count - see the 32-count comment above.
        assertThat(nonZeroWeightAnalysis.constraintMap()).hasSize(33);
    }

    // ─────────────────────────────────────────────────────────────────── (d) diff() weight preservation

    /**
     * Property (d): does {@code ScoreAnalysis.diff()} preserve {@code ConstraintAnalysis.weight()} on
     * the diffed result?
     *
     * <p><b>Verdict: NO - {@code diff()} SUBTRACTS the weight field, exactly the same way it subtracts
     * score</b> ({@code diffed.weight() = receiver.weight() - argument.weight()}, i.e. {@code
     * a.diff(b).weight() == a.weight().subtract(b.weight())}). This is proven, not merely inferred,
     * below: same-weight operands (first half) give weight ZERO on the diff (777 - 777 = 0), which by
     * itself is ambiguous between "diff subtracts weight" and "diff always reports zero for weight"; a
     * genuinely conclusive experiment (second half) diffs two {@code ScoreAnalysis} computed under
     * DIFFERENT weight vectors for the SAME constraint (the "receiver", {@code movedHighWeight} - the
     * POST-MOVE state analyzed under weight 777 - against the "argument", {@code baseLowWeight} - a
     * SEPARATE re-analysis of the ORIGINAL, PRE-MOVE baseline state under weight 1) and gets weight
     * {@code 777 - 1 = 776} (with sign: {@code -777 - (-1) = -776}, since {@code sameGroupSoft} is a
     * {@code .penalize} constraint - see class javadoc on the sign convention) - which is neither
     * operand's weight, ruling out both "preserves receiver's" and "preserves argument's" and leaving
     * only "subtracts" as consistent with the observation. The two operands being DIFFERENT solution
     * states (not just different weights) does not confound this: {@code ConstraintAnalysis.weight()}
     * is a per-constraint constant of the weight vector an analysis was computed under - it does not
     * depend on how many times (if any) the constraint matched in that state (see (a)'s and (c)'s own
     * findings), so which state each operand represents is irrelevant to what its {@code weight()}
     * reports.
     *
     * <p><b>Consequence</b>: {@code diff.constraintAnalyses()....weight()} is USELESS for reading "the
     * applied weight" whenever (the common case for {@link MoveProbe}, which always diffs two analyses
     * of the SAME weight vector) both diffed sides share a weight - it is always zero then, by
     * construction. Anything that needs "the weight this constraint was scored under" - like this
     * spike's own {@code units_k} derivation in property (b) - MUST read it off one of the RAW
     * (non-diffed) {@code ScoreAnalysis} operands instead.
     */
    @Test
    void diffSubtractsConstraintWeightJustLikeScore_soDiffWeightIsUselessAcrossSameWeightOperands() {
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> manager = SolutionManager.create(solverFactory());
        GroupPlanSolution solution = buildUnited(ConstraintWeightOverrides.of(
                Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(777))));
        Group g1 = groupOf(solution, 1L);
        Group g2 = groupOf(solution, 2L);
        PlayerAssignment target = playerOf(solution, PARTICIPANT_A);

        ScoreAnalysis<HardMediumSoftLongScore> baseHighWeight = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g2);
        ScoreAnalysis<HardMediumSoftLongScore> movedHighWeight = manager.analyze(solution, FETCH_ALL);
        target.setGroup(g1);

        // The RAW applied weight, read directly (never from a diff): -777 (penalize constraint).
        assertThat(analysisOf(movedHighWeight, ConstraintKeys.SAME_GROUP_SOFT).weight())
                .isEqualTo(HardMediumSoftLongScore.ofSoft(-777));

        // Same-weight diff: weight collapses to ZERO - ambiguous in isolation (777-777 and "always 0"
        // both predict this), so not yet conclusive on its own.
        ScoreAnalysis<HardMediumSoftLongScore> sameWeightDiff = movedHighWeight.diff(baseHighWeight);
        assertThat(analysisOf(sameWeightDiff, ConstraintKeys.SAME_GROUP_SOFT).weight())
                .isEqualTo(HardMediumSoftLongScore.ZERO);

        // Conclusive: re-analyze the IDENTICAL baseline (united) state under a DIFFERENT weight (1, not
        // 777), then diff the ORIGINAL moved-under-777 analysis against THAT. "Preserves the receiver"
        // would give -777; "preserves the argument" would give -1; only "subtracts" explains -776.
        solution.setConstraintWeightOverrides(
                ConstraintWeightOverrides.of(Map.of(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(1))));
        ScoreAnalysis<HardMediumSoftLongScore> baseLowWeight = manager.analyze(solution, FETCH_ALL);
        assertThat(analysisOf(baseLowWeight, ConstraintKeys.SAME_GROUP_SOFT).weight())
                .isEqualTo(HardMediumSoftLongScore.ofSoft(-1));

        ScoreAnalysis<HardMediumSoftLongScore> crossWeightDiff = movedHighWeight.diff(baseLowWeight);
        assertThat(analysisOf(crossWeightDiff, ConstraintKeys.SAME_GROUP_SOFT).weight())
                .as("diff() subtracts weight: -777 - (-1) = -776, neither operand's own weight")
                .isEqualTo(HardMediumSoftLongScore.ofSoft(-776));

        // And the reverse direction confirms it is a genuine (non-commutative) subtraction, not some
        // other combination: -1 - (-777) = +776.
        ScoreAnalysis<HardMediumSoftLongScore> crossWeightDiffReversed = baseLowWeight.diff(movedHighWeight);
        assertThat(analysisOf(crossWeightDiffReversed, ConstraintKeys.SAME_GROUP_SOFT).weight())
                .isEqualTo(HardMediumSoftLongScore.ofSoft(776));
    }

    // ─────────────────────────────────────────────────────────────────── shared fixture plumbing

    /**
     * Deliberately NOT {@link se.klubb.groupplanner.solver.TestSolverFactory}, mirroring {@code
     * WeightOverrideFlipTest}'s own choice and its documented rationale (m6a-notes.md "Review fix 1"
     * converged-fixture pathology) even though - unlike that test - THIS class never calls {@code
     * buildSolver().solve()} at all (only {@code SolutionManager.analyze}, which never runs local
     * search and so can never hit that pathology): kept for consistency with the fixture this class
     * forks, and because a future variant of this test that also solves would hit the exact same
     * issue. No {@code moveCountLimit}/{@code TerminationConfig} tuning is needed here since {@code
     * analyze()} never terminates a search - it just scores the object graph once per call.
     */
    private static SolverFactory<GroupPlanSolution> solverFactory() {
        SolverConfig config =
                SolverConfig.createFromXmlResource("solverConfig.xml").withEnvironmentMode(EnvironmentMode.PHASE_ASSERT);
        return SolverFactory.create(config);
    }

    /**
     * The fork's baseline layout, "united": pinned C,D in G1 / E,F in G2 (identical to {@code
     * WeightOverrideFlipTest}), PLUS the two free players A,B BOTH in G1 (the "unite" outcome from
     * that test's trade-off) so that moving A alone to G2 (see the two {@code @Test} methods above)
     * both splits the A/B wish AND rebalances group size in one move. Unlike the forked fixture, A/B
     * are NOT level-neutral (A=70 000, B=30 000 scaled, vs. 50 000 for everyone else) and A carries an
     * explicit time preference for G1's slot (1) - both deliberately chosen so the same single move
     * also exercises {@code levelBalance}, {@code groupOrderByLevel}, and {@code timePreferenceSoft},
     * not just {@code groupSizeTarget}/{@code sameGroupSoft} as in the original two-constraint fixture.
     */
    private static GroupPlanSolution buildUnited(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        Group g1 = new Group(1L, "Grupp 1", 1, 0, 3, 4, 0, 0, 100_000);
        Group g2 = new Group(2L, "Grupp 2", 2, 0, 3, 4, 0, 0, 100_000);
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        List<GroupSchedule> schedules =
                List.of(new GroupSchedule(1L, g1, b1, true), new GroupSchedule(2L, g2, b2, true));

        PlayerAssignment c = pinned(1L, g1, 50_000);
        PlayerAssignment d = pinned(2L, g1, 50_000);
        PlayerAssignment e = pinned(3L, g2, 50_000);
        PlayerAssignment f = pinned(4L, g2, 50_000);
        PlayerAssignment a = new PlayerAssignment(
                PARTICIPANT_A, PARTICIPANT_A, "PA", 70_000, 3, null, new long[0], new long[] {1L}, g1, false);
        PlayerAssignment b = new PlayerAssignment(
                PARTICIPANT_B, PARTICIPANT_B, "PB", 30_000, 3, null, new long[0], new long[0], g1, false);

        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_SAME, PARTICIPANT_A, PARTICIPANT_B);

        return new GroupPlanSolution(
                "test-plan",
                List.of(c, d, e, f, a, b),
                schedules,
                List.of(),
                List.of(g1, g2),
                List.of(b1, b2),
                List.of(),
                List.of(wish),
                List.of(),
                List.of(),
                LateTimePolicy.DISABLED,
                overrides);
    }

    /** Same fixture as {@link #buildUnited}, except A starts in G2 (already split from B) - used by
     * (a)/(c), which need a FIXED state where {@code sameGroupSoft} is broken (nonzero at default
     * weight) without performing any move. */
    private static GroupPlanSolution buildSplit(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        GroupPlanSolution united = buildUnited(overrides);
        playerOf(united, PARTICIPANT_A).setGroup(groupOf(united, 2L));
        return united;
    }

    private static PlayerAssignment pinned(long id, Group group, int levelScaled) {
        return new PlayerAssignment(id, id, "P" + id, levelScaled, 3, null, new long[0], new long[0], group, true);
    }

    private static Group groupOf(GroupPlanSolution solution, long groupId) {
        return solution.getGroups().stream().filter(g -> g.id() == groupId).findFirst().orElseThrow();
    }

    private static PlayerAssignment playerOf(GroupPlanSolution solution, long participantId) {
        return solution.getPlayerAssignments().stream()
                .filter(p -> p.getId() == participantId)
                .findFirst()
                .orElseThrow();
    }

    private static ConstraintAnalysis<HardMediumSoftLongScore> analysisOf(
            ScoreAnalysis<HardMediumSoftLongScore> analysis, String constraintKey) {
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : analysis.constraintMap().values()) {
            if (constraintKey.equals(ca.constraintRef().constraintName())) {
                return ca;
            }
        }
        throw new AssertionError("No constraint analysis entry for key " + constraintKey);
    }

    private static HardMediumSoftLongScore scoreOf(ScoreAnalysis<HardMediumSoftLongScore> analysis, String constraintKey) {
        return analysisOf(analysis, constraintKey).score();
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
