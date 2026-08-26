package se.klubb.groupplanner.fields;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.fields.PriorityOrder.Priority;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * v0.6.0 milestone B7: {@code GET|PUT /api/plans/{planId}/priority-order} — turns {@link
 * PriorityOrder}'s 4-priority ranking / weight ladder into a user-facing view backed by {@link
 * ConstraintWeightService} (the single source of truth for effective weights; this class never
 * re-reads {@code constraint_weight_config} directly for weight/enabled/hardOrSoft data).
 */
@Service
public class PriorityOrderService {

    /** All 24 permutations of {@link Priority#values()} — used by {@link #matchesOrder} to find
     * (if any) the permutation whose {@link PriorityOrder#weightsFor} output exactly reproduces the
     * plan's current effective weights for the 6 bucket keys. Order among the 24 is irrelevant: at
     * most one permutation can ever match, since {@link PriorityOrder#UNIT_LADDER}/{@link
     * PriorityOrder#LEVEL_LADDER} are strictly decreasing (no two ranks share a weight). */
    private static final List<List<Priority>> ALL_PERMUTATIONS = allPermutations();

    /** Fixed, human-meaningful order for each priority's {@link ConstraintKeys} — {@link
     * PriorityOrder#constraintKeysOf} returns an unordered {@code Set}, which would make the
     * response's {@code constraintKeys}/{@code weights} field order nondeterministic. */
    private static final Map<Priority, List<String>> ORDERED_CONSTRAINT_KEYS = orderedConstraintKeys();

    /** The plain-language "what this priority does" clause, rank-agnostic — combined with a
     * rank-specific suffix in {@link #summarySv} to produce one finished sentence. At {@link
     * PriorityOrder#defaultOrder()} (TRAIN_TOGETHER=1, PREVIOUS_GROUP=2, PREFERRED_TIME=3, LEVEL=4)
     * this reproduces the four example sentences from the B7 milestone brief (rank 1/4 suffixes
     * revised per B7 review — see {@link #summarySv}):
     * <ul>
     *   <li>"Spelare som önskat varandra hamnar i samma grupp. Det väger tyngst av allt."
     *   <li>"Spelare får fortsätta i sin tidigare grupp när det går."
     *   <li>"Önskad träningstid uppfylls när det inte krockar med viktigare önskemål."
     *   <li>"Grupperna hålls jämna i nivå – vägs in sist."
     * </ul>
     */
    private static final Map<Priority, String> ACTION_CLAUSE_SV = actionClausesSv();

    private final ConstraintWeightService constraintWeightService;
    private final ActivityPlanRepository activityPlanRepository;
    private final OptimizationRunRepository optimizationRunRepository;

    public PriorityOrderService(
            ConstraintWeightService constraintWeightService,
            ActivityPlanRepository activityPlanRepository,
            OptimizationRunRepository optimizationRunRepository) {
        this.constraintWeightService = constraintWeightService;
        this.activityPlanRepository = activityPlanRepository;
        this.optimizationRunRepository = optimizationRunRepository;
    }

    public PriorityOrderView getForPlan(String planId) {
        return buildView(planId, constraintWeightService.listForPlan(planId));
    }

    /**
     * Validates {@code orderKeys} (exactly 4 entries, a permutation of all {@link Priority} enum
     * names — 400 with a Swedish message otherwise), writes the 6 bucket keys' weights via {@link
     * ConstraintWeightService#applyOverrides} (reusing its validation + atomic revision bump), and
     * returns the recomputed view. One call is authoritative for the 6 bucket keys — this IS "reset
     * from custom weights" for them; every other constraint's override (if any) is untouched.
     *
     * <p><b>Restores {@link HardOrSoft#SOFT}/{@code enabled=true} on all 6 bucket keys (B7 review
     * fix).</b> Every request in the batch below explicitly sets {@code hardOrSoft=SOFT} and {@code
     * enabled=true} — NOT {@code null} ("keep current value", per {@link
     * ConstraintWeightOverrideRequest}'s javadoc). A bucket key previously disabled (via {@code PUT
     * /constraint-weights}) or reclassified HARD would otherwise stay broken forever after this call:
     * {@code applyOverrides} treats a {@code null} field as "preserve", so the weight alone would
     * change while the key stayed disabled/HARD, {@link #matchesOrder} would still report {@code
     * false} (it requires {@code enabled && SOFT}, see {@link #matchesExactly}), and — since this
     * endpoint never lets the caller touch {@code hardOrSoft}/{@code enabled} directly — there would
     * be no way back from this endpoint. All six bucket keys are seeded {@link HardOrSoft#SOFT} and
     * none is {@link HardOrSoft#MEDIUM}-reserved or in {@link
     * ConstraintWeightService}'s never-disableable set, so {@code validateReclassification} always
     * accepts {@code (SOFT, weight, true)} for them; this call is TRUE "reset to a valid ladder
     * permutation" for the 6 bucket keys, unconditionally.
     */
    @Transactional
    public PriorityOrderView updateForPlan(String planId, List<String> orderKeys) {
        List<Priority> order = parseOrder(orderKeys);
        Map<String, Integer> weights = PriorityOrder.weightsFor(order);
        List<ConstraintWeightOverrideRequest> requests = weights.entrySet().stream()
                .map(entry -> new ConstraintWeightOverrideRequest(
                        entry.getKey(), HardOrSoft.SOFT, entry.getValue(), Boolean.TRUE))
                .toList();
        List<ConstraintWeightView> rows = constraintWeightService.applyOverrides(planId, requests);
        return buildView(planId, rows);
    }

    // ─────────────────────────────────────────────────────────────────────── PUT validation

    private List<Priority> parseOrder(List<String> orderKeys) {
        if (orderKeys == null || orderKeys.size() != Priority.values().length) {
            throw new BadRequestException(
                    "Prioritetsordningen måste innehålla exakt fyra prioriteter, en gång vardera: " + validValuesSv());
        }
        List<Priority> parsed = new ArrayList<>(orderKeys.size());
        for (String raw : orderKeys) {
            parsed.add(parsePriority(raw));
        }
        if (Set.copyOf(parsed).size() != Priority.values().length) {
            throw new BadRequestException(
                    "Prioritetsordningen får inte innehålla dubbletter - varje prioritet exakt en gång: " + validValuesSv());
        }
        return List.copyOf(parsed);
    }

    private Priority parsePriority(String raw) {
        if (raw != null) {
            for (Priority priority : Priority.values()) {
                if (priority.name().equals(raw)) {
                    return priority;
                }
            }
        }
        throw new BadRequestException("Okänd prioritet: '" + raw + "'. Giltiga värden: " + validValuesSv());
    }

    private static String validValuesSv() {
        return Arrays.stream(Priority.values()).map(Priority::name).collect(Collectors.joining(", "));
    }

    // ─────────────────────────────────────────────────────────────────────── view assembly

    private PriorityOrderView buildView(String planId, List<ConstraintWeightView> rows) {
        Map<String, ConstraintWeightView> byKey =
                rows.stream().collect(Collectors.toMap(ConstraintWeightView::key, row -> row));

        Optional<List<Priority>> exactMatch = findMatchingPermutation(byKey);
        boolean matchesOrder = exactMatch.isPresent();
        List<Priority> effectiveOrder = exactMatch.orElseGet(() -> inferOrder(byKey));

        boolean otherOverridesActive = rows.stream()
                .filter(row -> PriorityOrder.bucketOf(row.key()).isEmpty())
                .anyMatch(ConstraintWeightView::overridden);

        return new PriorityOrderView(
                names(effectiveOrder),
                names(PriorityOrder.defaultOrder()),
                matchesOrder,
                !matchesOrder,
                otherOverridesActive,
                isStaleSinceLastRun(planId),
                latestUpdatedAt(byKey),
                buildRows(effectiveOrder, byKey));
    }

    /** Tries every one of the 24 permutations against {@link PriorityOrder#weightsFor} and returns
     * the one (there can be at most one, see {@link #ALL_PERMUTATIONS}) whose 6 expected weights are
     * ALL present, exactly equal to the current effective weight, enabled, and still classified SOFT
     * (a disabled or reclassified bucket key can never "match" any order, per the B7 spec). */
    private Optional<List<Priority>> findMatchingPermutation(Map<String, ConstraintWeightView> byKey) {
        for (List<Priority> candidate : ALL_PERMUTATIONS) {
            if (matchesExactly(candidate, byKey)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesExactly(List<Priority> candidate, Map<String, ConstraintWeightView> byKey) {
        Map<String, Integer> expected = PriorityOrder.weightsFor(candidate);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            ConstraintWeightView row = byKey.get(entry.getKey());
            if (row == null
                    || row.weight() != entry.getValue()
                    || !row.enabled()
                    || !HardOrSoft.SOFT.equals(row.hardOrSoft())) {
                return false;
            }
        }
        return true;
    }

    /** Best-effort order inference for {@code customWeightsActive} plans (B7 spec): each priority's
     * representative "normalized marginal" — {@code sameGroupSoft} for TRAIN_TOGETHER, {@code
     * previousGroupContinuity} for PREVIOUS_GROUP, {@code timePreferenceSoft} for PREFERRED_TIME,
     * and {@code levelBalance} &#215; 7 for LEVEL (one level-balance "band" &#8776; 7 spread units,
     * see {@link PriorityOrder} class javadoc) — sorted descending, ties broken by {@link
     * PriorityOrder#defaultOrder()} position. */
    private static List<Priority> inferOrder(Map<String, ConstraintWeightView> byKey) {
        Map<Priority, Integer> marginal = new EnumMap<>(Priority.class);
        marginal.put(Priority.TRAIN_TOGETHER, weightOf(byKey, ConstraintKeys.SAME_GROUP_SOFT));
        marginal.put(Priority.PREVIOUS_GROUP, weightOf(byKey, ConstraintKeys.PREVIOUS_GROUP_CONTINUITY));
        marginal.put(Priority.PREFERRED_TIME, weightOf(byKey, ConstraintKeys.TIME_PREFERENCE_SOFT));
        marginal.put(Priority.LEVEL, weightOf(byKey, ConstraintKeys.LEVEL_BALANCE) * 7);

        List<Priority> defaultOrder = PriorityOrder.defaultOrder();
        List<Priority> ranked = new ArrayList<>(List.of(Priority.values()));
        ranked.sort(Comparator.<Priority>comparingInt(p -> -marginal.get(p))
                .thenComparingInt(defaultOrder::indexOf));
        return List.copyOf(ranked);
    }

    /** A disabled bucket key's effective weight is 0 for every purpose here (both {@link
     * #inferOrder}'s ranking marginal and {@link #buildRows}'s displayed {@code weights} map) — the
     * solver ignores a disabled constraint entirely, so reporting its configured weight (B7 review
     * finding: a disabled {@code timePreferenceSoft} at weight 2400 used to infer PREFERRED_TIME as
     * rank 1 "väger tyngst av allt" for a constraint the solver never applies) would misrepresent
     * what actually drives the solve. A HARD-reclassified-but-still-ENABLED key's weight is reported
     * as-is (unchanged): the solver still applies it, just not as a soft trade-off weight, so zeroing
     * it here would be equally misleading in the other direction. */
    private static int weightOf(Map<String, ConstraintWeightView> byKey, String key) {
        ConstraintWeightView row = byKey.get(key);
        if (row == null || !row.enabled()) {
            return 0;
        }
        return row.weight();
    }

    /**
     * Staleness envelope (v0.6.0 milestone B7): {@code activity_plan.plan_revision} (bumped by
     * {@link ConstraintWeightService#applyOverrides}, among every other plan mutation) vs. the plan's
     * most recent FINISHED {@link OptimizationRun#planRevision} ("basedOnRevision" — the revision the
     * run's own writeback left the plan at), via {@link
     * OptimizationRunRepository#findLatestFinishedByActivityPlanId}.
     *
     * <p><b>NOT the exact same mechanism M7's explain/what-if staleness uses (B7 review fix — this
     * javadoc previously overclaimed that it was)</b>: {@code
     * se.klubb.groupplanner.explain.ExplanationService#loadContext} compares against a SPECIFIC,
     * caller-supplied {@code runId} the frontend already holds (e.g. "is the run I'm looking at still
     * current"). This method has no {@code runId} input — it compares against whichever run happens
     * to be the plan's latest FINISHED one. Only FINISHED runs are considered, deliberately: a
     * QUEUED/SOLVING/FAILED run's {@code planRevision} is 0 (never written back — see {@link
     * OptimizationRun} javadoc), and treating one of those as "the plan's latest run" would otherwise
     * permanently pin this flag {@code true} the moment the plan has ever had a single revision bump,
     * regardless of whether anything is actually amiss.
     *
     * <p><b>Honesty note (per the B7 brief):</b> this data model only supports "has ANYTHING changed
     * about the plan since its last successful solve" — it is not weight-specific. A participant edit,
     * a manual move, or a lock/unlock ALSO flips this flag, not just a priority-order/weight change.
     * The field is named for what it is used for (the frontend's "Kör om optimeringen" callout after a
     * priority-order edit), not for what it precisely detects: over-triggering (a stale flag for an
     * unrelated change) is acceptable for that callout; under-triggering would not be — and this
     * mechanism never under-triggers a weight change made THROUGH {@code applyOverrides} specifically
     * (it bumps the revision unconditionally), but it CAN under-trigger through a different channel: a
     * defaults-retempering Flyway migration (e.g. V13__priority_order_default_weights.sql, which
     * changed {@code constraint_definition.default_weight} for 13 keys) changes the EFFECTIVE weight
     * of any plan that has never overridden that constraint — {@link ConstraintWeightService
     * #mergeView} falls back to {@code def.defaultWeight()} for such plans — WITHOUT bumping that
     * plan's {@code plan_revision} at all, since the migration never touches {@code activity_plan}.
     * This flag would not notice a change like that. No FINISHED run at all for the plan yet is NOT
     * stale (nothing to be stale relative to).
     */
    private boolean isStaleSinceLastRun(String planId) {
        Optional<OptimizationRun> latestFinishedRun = optimizationRunRepository.findLatestFinishedByActivityPlanId(planId);
        if (latestFinishedRun.isEmpty()) {
            return false;
        }
        int currentRevision = activityPlanRepository.getPlanRevision(planId);
        return currentRevision != latestFinishedRun.get().planRevision();
    }

    /** The MAX of the 6 bucket keys' {@code updatedAt} values, compared as {@link Instant}s (B7
     * review fix) — NOT as raw strings. {@link Instant#toString()} omits trailing-zero fractional
     * digits (e.g. {@code "2026-01-01T10:00:00Z"} vs {@code "2026-01-01T10:00:00.900Z"}), so a plain
     * {@link String#compareTo} would rank the WHOLE-SECOND timestamp (no {@code "."} char, which
     * sorts after every digit) as LATER than a timestamp 900ms after it — silently reporting a stale
     * {@code updatedAt} whenever two override rows happen to straddle a whole-second boundary that
     * way. */
    private static String latestUpdatedAt(Map<String, ConstraintWeightView> byKey) {
        String latest = null;
        for (Priority priority : Priority.values()) {
            for (String key : ORDERED_CONSTRAINT_KEYS.get(priority)) {
                ConstraintWeightView row = byKey.get(key);
                if (row != null && row.updatedAt() != null
                        && (latest == null || Instant.parse(row.updatedAt()).isAfter(Instant.parse(latest)))) {
                    latest = row.updatedAt();
                }
            }
        }
        return latest;
    }

    private static List<PriorityOrderRow> buildRows(List<Priority> order, Map<String, ConstraintWeightView> byKey) {
        List<PriorityOrderRow> result = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            Priority priority = order.get(i);
            int rank = i + 1;
            List<String> keys = ORDERED_CONSTRAINT_KEYS.get(priority);
            Map<String, Integer> weights = new LinkedHashMap<>();
            for (String key : keys) {
                weights.put(key, weightOf(byKey, key));
            }
            result.add(new PriorityOrderRow(
                    priority.name(), rank, PriorityOrder.labelSv(priority), summarySv(priority, rank),
                    keys, Map.copyOf(weights), rowEnabled(keys, byKey)));
        }
        return result;
    }

    /** A row is {@code enabled} only when EVERY one of its {@link ConstraintKeys} is enabled (B7
     * review fix) — so the frontend can grey out a priority row whose current effect on the solve is
     * partially or wholly zeroed out via {@link #weightOf}, rather than showing a rank/weight that
     * implies the constraint is live when the solver is actually ignoring it. */
    private static boolean rowEnabled(List<String> keys, Map<String, ConstraintWeightView> byKey) {
        for (String key : keys) {
            ConstraintWeightView row = byKey.get(key);
            if (row == null || !row.enabled()) {
                return false;
            }
        }
        return true;
    }

    /** One finished Swedish sentence: {@link #ACTION_CLAUSE_SV}'s clause for {@code priority} plus a
     * rank-specific suffix (rank 1 = "weighs heaviest of all" as its OWN sentence — see below; rank 4
     * = "weighed in last", not an unconditional bare period). Every one of the 4 clauses × 4 rank
     * suffixes (16 combinations, see {@code PriorityOrderControllerTest}) must read as grammatical
     * Swedish; this is exercised for all 24 permutations by {@code allTwentyFourPermutationsRoundTrip}
     * indirectly and pinned directly for the default order by {@code
     * getOnFreshPlanReturnsDefaultsMatchingAndUnstamped}.
     *
     * <p><b>Rank 1 is two sentences (B7 review fix), not one clause with a trailing dash-clause.</b>
     * {@code action + " – väger tyngst av allt."} reads as "Grupperna hålls jämna i nivå – väger
     * tyngst av allt." for the LEVEL clause — grammatically "väger" (a verb needing a subject) then
     * attaches to "Grupperna" (the nearest preceding noun phrase), which is wrong: it is the PRIORITY
     * itself, not literally "grupperna", that "weighs heaviest of all" when ranked 1st. Restructuring
     * as {@code action + ". Det väger tyngst av allt."} ("Det" = "it", referring back to the priority
     * as a whole across the sentence boundary) removes the ambiguity for all four clauses.
     */
    private static String summarySv(Priority priority, int rank) {
        String action = ACTION_CLAUSE_SV.get(priority);
        return switch (rank) {
            case 1 -> action + ". Det väger tyngst av allt.";
            case 2 -> action + " när det går.";
            case 3 -> action + " när det inte krockar med viktigare önskemål.";
            default -> action + " – vägs in sist.";
        };
    }

    private static List<String> names(List<Priority> priorities) {
        return priorities.stream().map(Priority::name).toList();
    }

    private static Map<Priority, List<String>> orderedConstraintKeys() {
        Map<Priority, List<String>> map = new EnumMap<>(Priority.class);
        map.put(Priority.TRAIN_TOGETHER, List.of(ConstraintKeys.SAME_GROUP_SOFT, ConstraintKeys.DIFFERENT_GROUP_SOFT));
        map.put(Priority.PREVIOUS_GROUP, List.of(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY));
        map.put(Priority.PREFERRED_TIME, List.of(ConstraintKeys.TIME_PREFERENCE_SOFT));
        map.put(Priority.LEVEL, List.of(ConstraintKeys.LEVEL_BALANCE, ConstraintKeys.GROUP_ORDER_BY_LEVEL));
        return Collections.unmodifiableMap(map);
    }

    private static Map<Priority, String> actionClausesSv() {
        Map<Priority, String> map = new EnumMap<>(Priority.class);
        map.put(Priority.TRAIN_TOGETHER, "Spelare som önskat varandra hamnar i samma grupp");
        map.put(Priority.PREVIOUS_GROUP, "Spelare får fortsätta i sin tidigare grupp");
        map.put(Priority.PREFERRED_TIME, "Önskad träningstid uppfylls");
        map.put(Priority.LEVEL, "Grupperna hålls jämna i nivå");
        return Collections.unmodifiableMap(map);
    }

    private static List<List<Priority>> allPermutations() {
        List<List<Priority>> result = new ArrayList<>();
        permute(new ArrayList<>(List.of(Priority.values())), 0, result);
        return List.copyOf(result);
    }

    private static void permute(List<Priority> arr, int k, List<List<Priority>> result) {
        if (k == arr.size()) {
            result.add(List.copyOf(arr));
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            Collections.swap(arr, k, i);
            permute(arr, k + 1, result);
            Collections.swap(arr, k, i);
        }
    }
}
