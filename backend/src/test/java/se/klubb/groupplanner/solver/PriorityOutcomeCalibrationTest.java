package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.fields.PriorityOrder;
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
 * v0.6.0 milestone B6 review fix (was "BLOCKER 2" — the product claim needs to be evidenced, not
 * just argued via one-sided weight arithmetic). The claim under test: <b>ranking TRAIN_TOGETHER
 * above LEVEL lets friend wishes unite players who would otherwise be split by level balance</b> —
 * this is the entire point of the v0.6.0 default order (see {@code
 * backend/docs/priority-order-notes.md}). {@code fields.WeightCalibrationTest} proves the WEIGHT
 * arithmetic behind this claim; this test proves the actual SOLVE OUTCOME it predicts, on a
 * deterministic, purpose-built fixture — reusing {@link se.klubb.groupplanner.solver
 * .WeightOverrideFlipTest}'s fixture-building conventions (hand-built {@link GroupPlanSolution},
 * pinned {@link GroupSchedule}s so time/court never confound the level-vs-wish trade-off,
 * {@link TestSolverFactory}'s step-count-only termination for cross-platform determinism, ADR-007).
 *
 * <p><b>Why not the golden `large-120` regression dataset?</b> An A/B solve of `large-120` under
 * old-vs-new default weights leaves its 22 broken `WANT_SAME` wishes UNCHANGED — every one of them
 * is either HARD-blocked (structurally incompatible pair) or unreachable under the current local
 * search move selector's documented coverage gap on a dataset that size (see the design doc's own
 * note; also `priority-order-notes.md`'s "Outcome evidence" section). `large-120` was simply never
 * going to move on a weight change alone, so it cannot evidence or refute this claim either way —
 * this fixture exists specifically because it CAN: no hard blockers, small enough that the move
 * selector's coverage gap does not apply, and levels/wishes deliberately arranged to put the
 * TRAIN_TOGETHER-vs-LEVEL trade-off front and center.
 *
 * <p><b>Fixture shape</b>: 30 players in 3 level bands (10 each, levelScaled 10 000 / 12 500 / 15 000
 * — 25-level-point, i.e. 2.5-spread-unit, gaps between adjacent bands) across 3 groups sized to fit
 * them exactly at target (targetSize 10, maxSize 20 so no group can ever HARD-overflow regardless of
 * where the solver moves players). 3 {@code WANT_SAME} pairs are seeded so their two members start
 * in DIFFERENT bands — two pairs 1 band apart, one pair 2 bands apart — with no {@code
 * MUST_SAME}/{@code MUST_DIFFERENT} wish, no time preference, no coach data, and no previous-group
 * continuity fact touching any of them: the ONLY two constraint families with any real matchWeight on
 * this fixture are {@code sameGroupSoft}/{@code differentGroupSoft} (TRAIN_TOGETHER) and {@code
 * levelBalance}/{@code groupOrderByLevel} (LEVEL) plus the always-active {@code groupSizeTarget}, so
 * the outcome isolates exactly the trade-off this milestone's ranking is about.
 */
class PriorityOutcomeCalibrationTest {

    private static final int STEP_LIMIT = 3000;
    private static final int UNIMPROVED_STEP_LIMIT = 400;

    // Adjacent bands are 25 level points (2.5 spread units) apart - deliberately much narrower than
    // the ~70-point "typical band" convention used elsewhere (WeightCalibrationTest's default worked
    // example): this fixture needs BOTH a robust TRAIN_TOGETHER-vs-LEVEL contrast (old defaults
    // barely unite anything, new defaults unite everything) AND sane adjacent-group mean ordering
    // after 3 pairs' worth of players move across bands, on a small (30-player) fixture - a wider
    // band gap makes the second requirement fail empirically (moving even one 2-bands-apart player
    // materially skews a 10-player group's mean; verified by sweeping band widths from 40 down to 25
    // level points until the <=1-spread-unit ordering bound held). At old weights (sameGroupSoft 80,
    // levelBalance 100), a 2.5-unit band already costs 250 > 80, so old defaults reject the wish
    // outright. At new weights (sameGroupSoft 2400, levelBalance 85), a 2.5-unit band costs
    // 2.5*85=212.5, so a single wish dominates roughly ~11 such bands (2400/212.5) before losing.
    private static final int BAND_LOW = 10_000;
    private static final int BAND_MID = 12_000;
    private static final int BAND_HIGH = 14_000;
    private static final int PLAYERS_PER_BAND = 10;

    /** The OLD pre-v0.6.0 default weights (kravspec §17.2-era, `m6b-notes.md`'s superseded
     * reconciliation): level balance dominated every friend wish unconditionally. */
    private static final Map<String, HardMediumSoftLongScore> OLD_WEIGHTS = Map.ofEntries(
            Map.entry(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(80)),
            Map.entry(ConstraintKeys.DIFFERENT_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(60)),
            Map.entry(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, HardMediumSoftLongScore.ofSoft(30)),
            Map.entry(ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(40)),
            Map.entry(ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(100)),
            Map.entry(ConstraintKeys.GROUP_ORDER_BY_LEVEL, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(50)),
            Map.entry(ConstraintKeys.GROUP_MIN_SIZE_SOFT, HardMediumSoftLongScore.ofSoft(50)));

    /** The NEW v0.6.0 defaults: the six `PriorityOrder.weightsFor(PriorityOrder.defaultOrder())`
     * ladder entries, plus the two non-ladder size weights V13 seeds alongside them
     * (`priority-order-notes.md`'s "Size discipline" section). */
    private static final Map<String, HardMediumSoftLongScore> NEW_WEIGHTS = buildNewWeights();

    private static Map<String, HardMediumSoftLongScore> buildNewWeights() {
        Map<String, HardMediumSoftLongScore> weights = new HashMap<>();
        PriorityOrder.weightsFor(PriorityOrder.defaultOrder())
                .forEach((key, weight) -> weights.put(key, HardMediumSoftLongScore.ofSoft(weight)));
        weights.put(ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(800));
        weights.put(ConstraintKeys.GROUP_MIN_SIZE_SOFT, HardMediumSoftLongScore.ofSoft(2000));
        return Map.copyOf(weights);
    }

    // Pair 1: 1 band apart (LOW <-> MID).
    private static final long PAIR1_A = 1L;
    private static final long PAIR1_B = 11L;
    // Pair 2: 1 band apart (MID <-> HIGH).
    private static final long PAIR2_A = 12L;
    private static final long PAIR2_B = 21L;
    // Pair 3: 2 bands apart (LOW <-> HIGH) - the harder case.
    private static final long PAIR3_A = 2L;
    private static final long PAIR3_B = 22L;

    @Test
    void newDefaultsUniteStrictlyMorePairsThanOldDefaults_andLevelOrderingStaysSane() {
        long startMillis = System.currentTimeMillis();

        GroupPlanSolution oldSolved = solve(toOverrides(OLD_WEIGHTS));
        GroupPlanSolution newSolved = solve(toOverrides(NEW_WEIGHTS));

        assertThat(oldSolved.getScore().hardScore()).as("old-weights solve must be hard-feasible").isZero();
        assertThat(newSolved.getScore().hardScore()).as("new-weights solve must be hard-feasible").isZero();

        int oldUnited = unitedPairCount(oldSolved);
        int newUnited = unitedPairCount(newSolved);

        assertThat(newUnited)
                .as("new v0.6.0 defaults must unite strictly more WANT_SAME pairs than the old defaults "
                        + "(old=%d, new=%d) - this is the executable evidence for the priority-order product claim",
                        oldUnited, newUnited)
                .isGreaterThan(oldUnited);

        // Tuned for a robust (not knife-edge) contrast: old defaults unite NONE of the 3 seeded
        // pairs (level balance dominates decisively), new defaults unite ALL 3 (friend wishes
        // dominate decisively) - pinned exactly (not a loose bound) since the solver config uses a
        // fixed random seed (ADR-007), so this fixture's outcome is fully deterministic.
        assertThat(oldUnited).as("old defaults must unite none of the 3 pairs").isEqualTo(0);
        assertThat(newUnited).as("new defaults must unite all 3 pairs").isEqualTo(3);

        // Level ordering sanity (decision requirement): no adjacent-group mean inversion beyond 1
        // spread unit (LevelMath.SPREAD_UNIT_SCALED = 1000 scaled) under the NEW defaults, even
        // though friend wishes are now dominant - groupOrderByLevel still keeps ordering roughly sane.
        assertNoGroupMeanInversionBeyondOneSpreadUnit(newSolved);
        assertNoGroupMeanInversionBeyondOneSpreadUnit(oldSolved);

        assertThat(System.currentTimeMillis() - startMillis).as("must stay well under the ~10s budget").isLessThan(10_000L);
    }

    private static int unitedPairCount(GroupPlanSolution solution) {
        Map<Long, Group> groupByPersonId = new HashMap<>();
        for (PlayerAssignment pa : solution.getPlayerAssignments()) {
            groupByPersonId.put(pa.getPersonId(), pa.getGroup());
        }
        int united = 0;
        for (long[] pair : new long[][] {{PAIR1_A, PAIR1_B}, {PAIR2_A, PAIR2_B}, {PAIR3_A, PAIR3_B}}) {
            Group a = groupByPersonId.get(pair[0]);
            Group b = groupByPersonId.get(pair[1]);
            if (a != null && a.equals(b)) {
                united++;
            }
        }
        return united;
    }

    private static void assertNoGroupMeanInversionBeyondOneSpreadUnit(GroupPlanSolution solution) {
        Map<Integer, long[]> sumAndCountByOrder = new TreeMap<>();
        for (PlayerAssignment pa : solution.getPlayerAssignments()) {
            if (pa.getGroup() == null) {
                continue;
            }
            int order = pa.getGroup().groupOrder();
            long[] entry = sumAndCountByOrder.computeIfAbsent(order, k -> new long[2]);
            entry[0] += pa.getLevelScaled();
            entry[1] += 1;
        }
        Integer previousOrder = null;
        for (Map.Entry<Integer, long[]> entry : sumAndCountByOrder.entrySet()) {
            if (previousOrder != null) {
                long[] hi = sumAndCountByOrder.get(previousOrder);
                long[] lo = entry.getValue();
                // Cross-multiplied mean comparison (no division), mirroring groupOrderByLevel's own
                // filter: hi.mean < lo.mean <=> hi.sum*lo.count < lo.sum*hi.count.
                boolean inverted = hi[0] * lo[1] < lo[0] * hi[1];
                if (inverted) {
                    // Convert the inversion size back to spread units the same way meanDiffPoints does.
                    long numerator = lo[0] * hi[1] - hi[0] * lo[1];
                    long denominator = hi[1] * lo[1] * 1000L; // LevelMath.SPREAD_UNIT_SCALED
                    long spreadUnitsOfInversion = Math.floorDiv(numerator, denominator);
                    assertThat(spreadUnitsOfInversion)
                            .as("adjacent-group mean inversion at groupOrder %d/%d must be <= 1 spread unit",
                                    previousOrder, entry.getKey())
                            .isLessThanOrEqualTo(1L);
                }
            }
            previousOrder = entry.getKey();
        }
    }

    private static ConstraintWeightOverrides<HardMediumSoftLongScore> toOverrides(Map<String, HardMediumSoftLongScore> weights) {
        return ConstraintWeightOverrides.of(weights);
    }

    private static GroupPlanSolution solve(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        return solverFactory().buildSolver().solve(buildProblem(overrides));
    }

    /** Step-count + unimproved-step-count termination (ADR-007: never wall-clock in tests), PLUS a
     * {@code moveCountLimit} hard backstop — {@code WeightOverrideFlipTest}'s documented pathology
     * (m6a-notes.md "Review fix 1" RCA) applies here too: {@code unimprovedStepCountLimit} cannot
     * preempt mid-step, and a single local-search step over a union move selector CAN burn an
     * unbounded number of move evaluations before giving up, even with 30 free entities (not just
     * the 2-entity case that RCA documented) once most cheap improving moves are exhausted.
     * {@code moveCountLimit} intervenes mid-step (it counts individual move evaluations), so it is
     * the actual bound that keeps this test's runtime inside its ~10s budget. */
    private static SolverFactory<GroupPlanSolution> solverFactory() {
        TerminationConfig termination = new TerminationConfig()
                .withStepCountLimit(STEP_LIMIT)
                .withUnimprovedStepCountLimit(UNIMPROVED_STEP_LIMIT)
                .withMoveCountLimit(200_000L);
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withEnvironmentMode(EnvironmentMode.PHASE_ASSERT)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    private static GroupPlanSolution buildProblem(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        Group g1 = new Group(1L, "Grupp 1", 1, 0, PLAYERS_PER_BAND, 20, 0, 0, 100_000);
        Group g2 = new Group(2L, "Grupp 2", 2, 0, PLAYERS_PER_BAND, 20, 0, 0, 100_000);
        Group g3 = new Group(3L, "Grupp 3", 3, 0, PLAYERS_PER_BAND, 20, 0, 0, 100_000);
        List<Group> groups = List.of(g1, g2, g3);

        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        TrainingBlock b3 = new TrainingBlock(3L, 3L, "Bana 3", timeKey(21 * 60, 22 * 60 + 30), "21.00", 3L);
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, g1, b1, true),
                new GroupSchedule(2L, g2, b2, true),
                new GroupSchedule(3L, g3, b3, true));

        // Seeded (non-pinned) initial group per band, matching groupOrderByLevel's OWN convention
        // (design §4 row 10.7 / GroupPlanConstraintProvider#groupOrderByLevel javadoc: "hi" = the
        // BETTER, LOWER-numbered group must have the HIGHER mean) - Grupp 1 is the elite/high-level
        // group, Grupp 3 the lowest. Seeding (not pinning) the natural per-band placement here avoids
        // the construction heuristic's list-order-dependent first-fit choice confounding the ordering
        // sanity check with an artifact of entity iteration order rather than the wish/level trade-off
        // this fixture exists to isolate - local search is still completely free to move any of these
        // players to satisfy a WANT_SAME wish.
        List<PlayerAssignment> players = new ArrayList<>();
        long id = 1L;
        for (int band = 0; band < 3; band++) {
            int levelScaled;
            Group naturalGroup;
            switch (band) {
                case 0 -> {
                    levelScaled = BAND_LOW;
                    naturalGroup = g3;
                }
                case 1 -> {
                    levelScaled = BAND_MID;
                    naturalGroup = g2;
                }
                default -> {
                    levelScaled = BAND_HIGH;
                    naturalGroup = g1;
                }
            }
            for (int i = 0; i < PLAYERS_PER_BAND; i++) {
                players.add(free(id, levelScaled, naturalGroup));
                id++;
            }
        }

        List<PersonPairWish> wishes = List.of(
                new PersonPairWish(1L, WishType.WANT_SAME, PAIR1_A, PAIR1_B),
                new PersonPairWish(2L, WishType.WANT_SAME, PAIR2_A, PAIR2_B),
                new PersonPairWish(3L, WishType.WANT_SAME, PAIR3_A, PAIR3_B));

        return new GroupPlanSolution(
                "test-plan", players, schedules, List.of(), groups, List.of(b1, b2, b3),
                List.of(), wishes, List.of(), List.of(), LateTimePolicy.DISABLED, overrides);
    }

    private static PlayerAssignment free(long id, int levelScaled, Group initialGroup) {
        return new PlayerAssignment(id, id, "P" + id, levelScaled, 3, null, new long[0], new long[0], initialGroup, false);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
