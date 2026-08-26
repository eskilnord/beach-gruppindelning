package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintReasonView;
import se.klubb.groupplanner.explain.ExplanationDtos.PrioritySensitivityView;
import se.klubb.groupplanner.explain.ExplanationDtos.ScoreDeltaView;
import se.klubb.groupplanner.explain.ExplanationDtos.UnmetWishView;
import se.klubb.groupplanner.explain.ExplanationService.RunContext;
import se.klubb.groupplanner.explain.UnmetWishResolver.UnmetWish;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * M-E2 "därför-meningen": turns one {@link UnmetWish} into a truthful, PROVABLE Swedish narrative —
 * every sentence either (a) quotes a literal fact (a lock, an empty candidate set, a group's own
 * data) or (b) is checked against the E1 probe map before being written (kravspec §17.4 "aldrig
 * gissningar", the project's hardest rule). Six outcomes, checked in this exact priority order (a
 * player can be LOCKED even if a candidate would also look hard-blocked, etc. — the FIRST matching
 * branch wins, never a "most severe" ranking):
 *
 * <ol>
 *   <li>{@code LOCKED} — {@code target.isPinned()}: the solver was never allowed to try any move at
 *       all, so nothing below can be honestly claimed.
 *   <li>{@code NO_CANDIDATE} — the wish's own candidate set (from {@link UnmetWishResolver}, DATA
 *       only) is empty: there is nothing to compare against.
 *   <li>{@code BLOCKED_HARD} — every candidate {@code wouldBreakHard()} (E1 probe fact, M-E2 review
 *       fix: match-based, not net-delta — see {@link MoveProbe.Result#wouldBreakHard()}).
 *   <li>{@code TRADE_OFF} / {@code EQUAL} / {@code SOLVER_MISS} — the least-bad candidate (picked via
 *       this class's OWN "no new hard breaks, then full lexicographic score delta, then group order"
 *       ordering — M-E2 review fix, no longer {@code ImprovementSuggestionService}'s
 *       |hard|-ascending ordering, which discarded hard-REPAIRING candidates) is respectively strictly
 *       worse, exactly equal, or strictly better than the current placement.
 * </ol>
 *
 * <p>{@code INCONCLUSIVE} is a seventh, defensive-only outcome: {@code TRADE_OFF}'s self-check
 * invariant (the wish's own SPECIFIC pair must appear among the chosen candidate's {@code
 * newlyFixed}) failing is a programming-error smell, not a claim this class will ever make to a user
 * — logged as a WARN and surfaced with an honest "couldn't determine" message rather than a
 * fabricated causal claim.
 *
 * <p>M-E2 review fix (BLOCKER, staleness): every outcome's {@code primaryReasonSv} — not just {@code
 * SOLVER_MISS} — is prefixed with {@link #STALE_PREFIX} whenever {@code ctx.stale()}, since a stale
 * run's probe map was computed against data that no longer matches the plan for EVERY outcome, not
 * just the one that happens to recommend a move.
 *
 * <p>Package-private, static-only (no state of its own): every fact it needs — the probe map, the
 * solver-quality WARN dedupe set — is handed in by {@link ExplanationService}, which already owns
 * both (E1's {@code buildAlternatives} refactor; the pre-existing {@code solverQualityWarned} set).
 */
final class CausalNarrator {

    private static final Logger log = LoggerFactory.getLogger(CausalNarrator.class);

    /** Banned lexicon (M-E2 brief): phrasing that overclaims certainty/causality beyond what a single-
     * move probe can prove. {@code CausalNarrativeTruthfulnessTest} sweeps every generated sentence
     * against this exact list — kept here, not duplicated in the test, so the test can never drift
     * from what the generator itself is forbidden to write. M-E2 review fix: banned the whole
     * "optimeringen valde" STEM (not just "...valde att") — the EQUAL outcome's own former text
     * ("Optimeringen valde en av flera likvärdiga lösningar") tripped exactly this overclaim (it
     * asserts the solver made a deliberate choice among alternatives it never actually compared). */
    static final List<String> BANNED_LEXICON = List.of(
            "kommer att hamna", "hamnar i", "garanterat", "alltid", "enda anledningen", "optimeringen valde",
            "skulle bli bättre för hela planen");

    /** M-E2 review fix (BLOCKER): prefixed onto EVERY outcome's {@code primaryReasonSv} when {@code
     * ctx.stale()} — the run's probe map was computed against a plan snapshot that no longer matches
     * the CURRENT data, so every causal claim below it is conditioned on "as of that snapshot", not
     * "right now". */
    private static final String STALE_PREFIX =
            "Planen har ändrats sedan optimeringen kördes. Med dagens data kan svaret nedan vara annorlunda. ";

    // ─────────────────────────────────────────────────────────────────────── M-E3 per-outcome sensitivity honesty
    //
    // Only TRADE_OFF names a question ("would reordering the four priorities help THIS move?") that is
    // even meaningful - every other outcome gets an honest available=false with its OWN reason, never
    // the old blanket "Beräknas i ett senare steg." placeholder.

    private static final PrioritySensitivityView SENSITIVITY_LOCKED = unavailableSensitivity(
            "Spelaren är låst till sin grupp – prioritetsordningen påverkar inget så länge låsningen gäller.");
    private static final PrioritySensitivityView SENSITIVITY_NO_CANDIDATE = unavailableSensitivity(
            "Ingen grupp kan uppfylla önskemålet – prioritetsordningen har inget att styra över här.");
    private static final PrioritySensitivityView SENSITIVITY_BLOCKED_HARD = unavailableSensitivity(
            "Ett hårt krav blockerar – ingen prioritetsordning påverkar det.");
    private static final PrioritySensitivityView SENSITIVITY_EQUAL = unavailableSensitivity(
            "Flytten kostar redan inga poäng – det finns inget att förbättra genom en annan prioritetsordning.");
    private static final PrioritySensitivityView SENSITIVITY_SOLVER_MISS = unavailableSensitivity(
            "Flytten skulle redan förbättra planen – prioritetsordningen är inte det som håller emot här.");
    private static final PrioritySensitivityView SENSITIVITY_INCONCLUSIVE = unavailableSensitivity(
            "Orsaken kunde inte säkert härledas, så känsligheten går inte att beräkna.");

    private static PrioritySensitivityView unavailableSensitivity(String reasonSv) {
        return new PrioritySensitivityView(false, reasonSv, null, null, null, null, null);
    }

    private CausalNarrator() {
    }

    static String lockedNoticeSv(RunContext ctx, PlayerAssignment target, Group selectedGroup, String timeLabelSv) {
        String where = timeLabelSv == null ? selectedGroup.name() : "%s (%s)".formatted(selectedGroup.name(), timeLabelSv);
        return "%s är låst till %s. Optimeringen fick inte flytta %s till någon annan grupp, så alternativen nedan visar "
                .formatted(target.getDisplayName(), where, target.getDisplayName())
                + "bara vad ett byte SKULLE innebära – inte vad optimeringen övervägde.";
    }

    static UnmetWishView narrate(
            ExplanationService svc,
            RunContext ctx,
            PlayerAssignment target,
            Group selectedGroup,
            UnmetWish wish,
            Map<Group, MoveProbe.Result> probesByGroup,
            Set<String> solverQualityWarned) {
        String bucket = PriorityOrder.bucketOf(wish.key()).map(Enum::name).orElse(null);
        String wishId = wishId(wish);
        String wishSv = wishSv(ctx, target, wish);
        List<String> candidateGroupIds = wish.candidateGroups().stream().map(g -> svc.groupDbId(ctx, g)).toList();

        if (target.isPinned()) {
            String reason = "%s är låst till %s (%s). Optimeringen fick inte flytta %s, så önskemålet kunde inte prövas. "
                    .formatted(target.getDisplayName(), selectedGroup.name(), timeLabel(svc, ctx, selectedGroup), target.getDisplayName())
                    + "Lås upp placeringen och kör om optimeringen om du vill att det ska testas.";
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "LOCKED", reason, hedgeSv(target), candidateGroupIds, null, null, List.of(),
                    SENSITIVITY_LOCKED);
        }

        if (wish.candidateGroups().isEmpty()) {
            String reason = noCandidateReason(ctx, target, wish);
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "NO_CANDIDATE", reason, hedgeSv(target), candidateGroupIds, null, null, List.of(),
                    SENSITIVITY_NO_CANDIDATE);
        }

        List<Group> candidates = wish.candidateGroups();
        boolean allBlocked = candidates.stream().allMatch(g -> requireResult(probesByGroup, g).wouldBreakHard());
        if (allBlocked) {
            String reason = blockedHardReason(svc, ctx, target, candidates, probesByGroup);
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "BLOCKED_HARD", reason, null, candidateGroupIds, null, null, List.of(),
                    SENSITIVITY_BLOCKED_HARD);
        }

        Group bestGroup = null;
        MoveProbe.Result bestResult = null;
        for (Group g : candidates) {
            MoveProbe.Result r = requireResult(probesByGroup, g);
            if (bestResult == null || isBetterCandidateForNarrator(r, g, bestResult, bestGroup)) {
                bestGroup = g;
                bestResult = r;
            }
        }
        String bestGroupDbId = svc.groupDbId(ctx, bestGroup);
        ScoreDeltaView bestDelta = ExplanationService.toScoreDeltaView(bestResult.scoreDelta());

        // M-E2 review fix (BLOCKER): EQUAL additionally requires no newly-broken hard match, not just
        // a net-zero delta (defensive - the "no new hard breaks first" ordering above already
        // guarantees this whenever a non-breaking candidate exists among the wish's own candidates).
        if (HardMediumSoftLongScore.ZERO.equals(bestResult.scoreDelta()) && !bestResult.wouldBreakHard()) {
            String reason = "En flytt till %s (%s) skulle ge exakt samma totalpoäng och bryter ingen regel. "
                    .formatted(bestGroup.name(), timeLabel(svc, ctx, bestGroup))
                    + "Flera likvärdiga lösningar finns. Du kan flytta %s manuellt utan att planen blir sämre."
                            .formatted(target.getDisplayName());
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "EQUAL", reason, hedgeSv(target), candidateGroupIds, bestGroupDbId, bestDelta,
                    List.of(), SENSITIVITY_EQUAL);
        }

        if (bestResult.isImprovement()) {
            String reason = "En flytt till %s (%s) skulle faktiskt förbättra planen. ".formatted(bestGroup.name(), timeLabel(svc, ctx, bestGroup))
                    + "Optimeringen hittade inte den bästa lösningen den här gången – kör om optimeringen (gärna med längre tid), "
                    + "eller flytta %s manuellt.".formatted(target.getDisplayName());
            if (!ctx.stale()) {
                String dedupeKey = ctx.run().id() + "|" + svc.participantDbId(ctx, target.getId());
                if (solverQualityWarned.add(dedupeKey)) {
                    log.warn(
                            "Solver-quality warning: placed participant {} has a hard-feasible unmet-wish candidate {} "
                                    + "that would improve the plan (run {}) - re-solving may improve the result.",
                            target.getDisplayName(), bestGroup.name(), ctx.run().id());
                }
            }
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "SOLVER_MISS", reason, null, candidateGroupIds, bestGroupDbId, bestDelta,
                    List.of(), SENSITIVITY_SOLVER_MISS);
        }

        // TRADE_OFF: self-check invariant first - the wish's own SPECIFIC pair (not merely its key)
        // must appear among what the best candidate would fix, or this is not a provable causal claim
        // (kravspec §17.4). M-E2 review fix (per-PAIR granularity): a candidate that fixes a DIFFERENT
        // pair sharing the same constraint key (e.g. a different sameGroupSoft friendship) must never
        // validate/inflate THIS wish's gain.
        long wishGain = sumAbsPrimaryForPair(bestResult.newlyFixedScored(), wish.key(), target.getId(), wish.otherParticipantSolverId());
        if (wishGain <= 0) {
            log.warn(
                    "CausalNarrator self-check failed for wish {} of participant {} (run {}): best candidate {} does not "
                            + "fix the wish's own key {} - refusing to make a causal TRADE_OFF claim.",
                    wishId, target.getDisplayName(), ctx.run().id(), bestGroup.name(), wish.key());
            String reason = "Det gick inte att säkert härleda orsaken här. Kör om optimeringen så beräknas förklaringen om.";
            return finish(
                    ctx, wishId, wish.key(), bucket, wishSv, "INCONCLUSIVE", reason, null, candidateGroupIds, null, null, List.of(),
                    SENSITIVITY_INCONCLUSIVE);
        }

        Map<String, List<MoveProbe.ScoredMatch>> brokenByKey = groupBrokenByKeyExcludingOwnPair(
                bestResult.newlyBrokenScored(), wish.key(), target.getId(), wish.otherParticipantSolverId());
        Map<String, Long> costByKey = new LinkedHashMap<>();
        for (Map.Entry<String, List<MoveProbe.ScoredMatch>> e : brokenByKey.entrySet()) {
            long sum = e.getValue().stream().mapToLong(m -> Math.abs(primaryComponent(m.matchScore()))).sum();
            costByKey.put(e.getKey(), sum);
        }
        long totalNegative = costByKey.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> ranked = rankedEntries(costByKey);

        List<ConstraintReasonView> competingReasons = new ArrayList<>();
        for (Map.Entry<String, Long> e : ranked) {
            List<MoveProbe.ScoredMatch> group = brokenByKey.get(e.getKey());
            String label = ConstraintMetadata.of(e.getKey()).label();
            String messageSv = messageForGroup(ctx, e.getKey(), group, target.getId());
            int sharePercent = totalNegative <= 0 ? 0 : (int) Math.floorDiv(100L * e.getValue(), totalNegative);
            competingReasons.add(new ConstraintReasonView(e.getKey(), label, messageSv, e.getValue(), sharePercent));
        }

        String reason = tradeOffReason(svc, ctx, target, selectedGroup, bestGroup, wish, ranked, brokenByKey, totalNegative, wishGain);
        Group finalBestGroup = bestGroup;
        List<Group> others = candidates.stream().filter(g -> g != finalBestGroup).toList();
        reason += sameAppliesClause(others);

        // M-E3: the real "vad skulle krävas?" computation - pure arithmetic over bestResult's own
        // perConstraint units (E1), zero additional analyze() calls.
        Map<String, HardMediumSoftLongScore> currentWeights = PrioritySensitivityCalculator.currentWeightsOf(ctx.solution());
        PrioritySensitivityCalculator.Computation sensitivityComputation =
                PrioritySensitivityCalculator.compute(bestResult.perConstraint(), currentWeights, wish.key(), bestGroup.name());
        PrioritySensitivityView sensitivity = PrioritySensitivityCalculator.toView(sensitivityComputation);

        return finish(ctx, wishId, wish.key(), bucket, wishSv, "TRADE_OFF", reason, hedgeSv(target), candidateGroupIds, bestGroupDbId, bestDelta,
                competingReasons, sensitivity);
    }

    /** M-E2 review fix (BLOCKER, "least-bad candidate ordering for the narrator"): no-new-hard-breaks
     * candidates first, then FULL lexicographic score delta (hard, medium, soft) descending via {@link
     * HardMediumSoftLongScore#compareTo}, then group order ascending. Deliberately NOT {@code
     * ImprovementSuggestionService#isBetterCandidate} (|hard|-ascending) — that ordering was built for
     * "smallest hard violation to complain about" suggestions and would happily rank a candidate that
     * BREAKS a hard constraint above one that doesn't merely because its |hard| delta is small,
     * discarding hard-REPAIRING candidates in the process. The suggestion service's own ordering is
     * left completely untouched; this is a separate, narrator-only comparator. */
    private static boolean isBetterCandidateForNarrator(MoveProbe.Result r, Group g, MoveProbe.Result best, Group bestGroup) {
        if (r.wouldBreakHard() != best.wouldBreakHard()) {
            return !r.wouldBreakHard();
        }
        int scoreCmp = r.scoreDelta().compareTo(best.scoreDelta());
        if (scoreCmp != 0) {
            return scoreCmp > 0;
        }
        return g.groupOrder() < bestGroup.groupOrder();
    }

    /** M-E2 review fix (BLOCKER, staleness on every outcome): wraps the outcome-specific {@code
     * reason} with {@link #STALE_PREFIX} when {@code ctx.stale()}, then builds the view - the single
     * choke point every {@code narrate()} branch now returns through, so no branch can forget it. */
    private static UnmetWishView finish(
            RunContext ctx, String wishId, String key, String bucket, String wishSv, String outcome, String primaryReasonSv, String hedgeSv,
            List<String> candidateGroupIds, String bestCandidateGroupId, ScoreDeltaView bestCandidateDelta,
            List<ConstraintReasonView> competingReasons, PrioritySensitivityView sensitivity) {
        String finalReason = primaryReasonSv == null || !ctx.stale() ? primaryReasonSv : STALE_PREFIX + primaryReasonSv;
        return view(wishId, key, bucket, wishSv, outcome, finalReason, hedgeSv, candidateGroupIds, bestCandidateGroupId, bestCandidateDelta,
                competingReasons, sensitivity);
    }

    // ─────────────────────────────────────────────────────────────────────── outcome-specific text

    /** M-E2 review fix (MAJOR, "scope honesty for NO_CANDIDATE"): schedule and coach assignments are
     * planning variables FROZEN by the single-move probe (§12.1's own deviation note) - a "no group
     * offers this" fact is honest only if it also says schedule/coach changes were never on the table
     * for this comparison. */
    private static String noCandidateReason(RunContext ctx, PlayerAssignment target, UnmetWish wish) {
        return switch (wish.wishKind()) {
            case "TIME" -> "Ingen grupp tränar %s i den nuvarande tidsplaneringen, så tidsönskemålet kunde inte uppfyllas utan att schemat görs om."
                    .formatted(preferredTimeLabels(ctx, target));
            case "FRIEND" -> {
                if (wish.friendWaitlisted()) {
                    String friend = ctx.index().participantName(wish.otherParticipantSolverId());
                    yield "%s är oplacerad (kölista), så önskemålet att spela med %s kunde inte uppfyllas.".formatted(friend, friend);
                }
                yield "Ingen grupp i planen kunde uppfylla önskemålet.";
            }
            case "PREVGROUP" -> "Den tidigare gruppen finns inte längre i planen, så önskemålet om samma grupp som förra terminen "
                    + "kunde inte prövas.";
            case "COACH" -> ("Ingen grupp har tränaren %s i den nuvarande tränarfördelningen, så tränarönskemålet kunde inte uppfyllas "
                    + "utan att tränarfördelningen görs om.").formatted(ctx.index().personName(wish.coachPersonSolverId()));
            case "AVOID" -> "Det finns ingen annan grupp att flytta till, så önskemålet kunde inte uppfyllas.";
            default -> "Önskemålet kunde inte uppfyllas.";
        };
    }

    // ─────────────────────────────────────────────────────────────────────── BLOCKED_HARD

    /** M-E2 review fix (BLOCKER, per-blocker-family remedies): the OLD single tail ("det krävs en
     * plats till...") was only ever true for the FULL-group family and got attached to every family
     * regardless — a wish blocked by a TIME/WISH/COACH mismatch got told to "raise the max size",
     * which fixes nothing. Each family below gets its OWN honest remedy clause, and "Samma sak gäller"
     * is only ever claimed for OTHER candidates whose {@link HardBlocker#shortReasonSv()} is
     * STRING-EQUAL to the first candidate's — otherwise each is listed with its OWN reason instead. */
    private static String blockedHardReason(
            ExplanationService svc, RunContext ctx, PlayerAssignment target, List<Group> candidates, Map<Group, MoveProbe.Result> probesByGroup) {
        Group first = candidates.get(0);
        HardBlocker firstBlocker = hardBlockerOf(ctx, target, first, requireResult(probesByGroup, first));
        String remedy = hardBlockerRemedySv(target, firstBlocker.family());
        String reason = "%s kunde inte flyttas till %s (%s) – %s. %s"
                .formatted(target.getDisplayName(), first.name(), timeLabel(svc, ctx, first), firstBlocker.shortReasonSv(), remedy);

        List<Group> others = candidates.stream().filter(g -> g != first).toList();
        if (others.isEmpty()) {
            return reason;
        }
        List<Group> sameReason = new ArrayList<>();
        List<Group> differentReason = new ArrayList<>();
        Map<Group, HardBlocker> otherBlockers = new LinkedHashMap<>();
        for (Group g : others) {
            HardBlocker gb = hardBlockerOf(ctx, target, g, requireResult(probesByGroup, g));
            otherBlockers.put(g, gb);
            (gb.shortReasonSv().equals(firstBlocker.shortReasonSv()) ? sameReason : differentReason).add(g);
        }
        StringBuilder sb = new StringBuilder(reason);
        if (!sameReason.isEmpty()) {
            sb.append(sameAppliesClause(sameReason));
        }
        if (!differentReason.isEmpty()) {
            sb.append(differentReasonClause(differentReason, otherBlockers));
        }
        return sb.toString();
    }

    private enum HardBlockerFamily { FULL, TIME, WISH, COACH, OTHER }

    private record HardBlocker(HardBlockerFamily family, String shortReasonSv) {
    }

    private static HardBlocker hardBlockerOf(RunContext ctx, PlayerAssignment target, Group candidate, MoveProbe.Result r) {
        boolean full = r.newlyBroken().stream().anyMatch(m -> ConstraintKeys.GROUP_MAX_SIZE_HARD.equals(m.key()));
        if (full) {
            MoveProbe.GroupStats stats = MoveProbe.statsOf(ctx.solution(), candidate);
            return new HardBlocker(HardBlockerFamily.FULL, "gruppen är full (%d/%d)".formatted(stats.size(), candidate.maxSize()));
        }
        boolean timeBlocked = r.newlyBroken().stream().anyMatch(m -> ConstraintKeys.TIME_AVAILABILITY_HARD.equals(m.key()));
        if (timeBlocked) {
            return new HardBlocker(HardBlockerFamily.TIME, "%s kan inte tiden för %s".formatted(target.getDisplayName(), candidate.name()));
        }
        boolean wishBlocked = r.newlyBroken().stream()
                .anyMatch(m -> ConstraintKeys.SAME_GROUP_HARD.equals(m.key()) || ConstraintKeys.DIFFERENT_GROUP_HARD.equals(m.key()));
        if (wishBlocked) {
            return new HardBlocker(HardBlockerFamily.WISH, "en flytt dit skulle bryta ett måste-krav om spelpartner");
        }
        boolean coachBlocked = r.newlyBroken().stream()
                .anyMatch(m -> ConstraintKeys.COACH_WISH_REQUIRED.equals(m.key()) || ConstraintKeys.COACH_WISH_FORBIDDEN.equals(m.key()));
        if (coachBlocked) {
            return new HardBlocker(HardBlockerFamily.COACH, "en flytt dit skulle bryta ett tränarkrav");
        }
        String fallback = r.newlyBroken().isEmpty() ? "gruppen går inte att flytta till just nu" : r.newlyBroken().get(0).messageSv();
        return new HardBlocker(HardBlockerFamily.OTHER, fallback);
    }

    private static String hardBlockerRemedySv(PlayerAssignment target, HardBlockerFamily family) {
        return switch (family) {
            case FULL -> "Ingen ändring av prioritetsordningen hjälper här – det krävs en plats till (höj maxstorleken eller flytta någon annan).";
            case TIME -> "%s kan inte den tiden – önskemålet kräver en annan tid eller ändrad tillgänglighet.".formatted(target.getDisplayName());
            case WISH -> "Måste-kravet om spelpartner måste lösas först (flytta den andra spelaren dit, eller ändra kravet) – ingen ändring "
                    + "av prioritetsordningen hjälper här.";
            case COACH -> "Tränarkravet måste lösas först (byt tränare, eller ändra kravet) – ingen ändring av prioritetsordningen hjälper här.";
            case OTHER -> "Ingen ändring av prioritetsordningen hjälper här – skälet ovan måste lösas först.";
        };
    }

    private static String differentReasonClause(List<Group> groups, Map<Group, HardBlocker> blockers) {
        List<Group> named = groups.size() > 2 ? groups.subList(0, 2) : groups;
        int remaining = groups.size() - named.size();
        List<String> items = new ArrayList<>(named.stream()
                .map(g -> "%s (%s)".formatted(g.name(), blockers.get(g).shortReasonSv()))
                .toList());
        if (remaining > 0) {
            items.add(remaining == 1 ? "1 grupp till" : "%d grupper till".formatted(remaining));
        }
        return " Även %s var blockerade, men av andra skäl.".formatted(joinSv(items));
    }

    // ─────────────────────────────────────────────────────────────────────── TRADE_OFF

    private static String tradeOffReason(
            ExplanationService svc, RunContext ctx, PlayerAssignment target, Group selectedGroup, Group bestGroup, UnmetWish wish,
            List<Map.Entry<String, Long>> ranked, Map<String, List<MoveProbe.ScoredMatch>> brokenByKey, long totalNegative, long wishGain) {
        String base = "%s står kvar i %s (%s) för att alternativet %s (%s) kostar mer: "
                .formatted(target.getDisplayName(), selectedGroup.name(), timeLabel(svc, ctx, selectedGroup),
                        bestGroup.name(), timeLabel(svc, ctx, bestGroup));
        String wishName = wishNameSv(ctx, wish);
        if (ranked.isEmpty() || totalNegative <= 0) {
            return base + "en flytt dit kostar mer sammantaget än vad %s är värt.".formatted(wishName);
        }
        Map.Entry<String, Long> top = ranked.get(0);
        long topShare = top.getValue();
        int sharePercent = (int) Math.floorDiv(100L * topShare, totalNegative);
        if (sharePercent >= 60) {
            String phrase = phraseFor(ctx, top.getKey(), brokenByKey.get(top.getKey()), target.getId());
            // M-E2 review fix (MAJOR, ratio honesty): computed within the SOFT level only - a
            // candidate can only reach TRADE_OFF with zero net hard/medium delta and zero hard
            // matches at all (see narrate()'s ordering/isImprovement gates above), so wishGain/
            // topShare are guaranteed soft-level scalars here, never a hard/medium magnitude.
            double ratio = wishGain <= 0 ? 0.0 : topShare / (double) wishGain;
            return base + "en flytt dit skulle bryta %s, som väger %s %s.".formatted(phrase, ratioWord(ratio), wishName);
        }
        long pairSum = ranked.size() >= 2 ? ranked.get(0).getValue() + ranked.get(1).getValue() : topShare;
        if (sharePercent >= 30 && ranked.size() >= 2 && pairSum > wishGain) {
            String phrase1 = phraseFor(ctx, ranked.get(0).getKey(), brokenByKey.get(ranked.get(0).getKey()), target.getId());
            String phrase2 = phraseFor(ctx, ranked.get(1).getKey(), brokenByKey.get(ranked.get(1).getKey()), target.getId());
            return base + "en flytt dit skulle bryta två saker som tillsammans väger tyngre än %s: %s och %s."
                    .formatted(wishName, phrase1, phrase2);
        }
        List<String> phrases = ranked.stream().limit(3)
                .map(e -> phraseFor(ctx, e.getKey(), brokenByKey.get(e.getKey()), target.getId()))
                .toList();
        return base + "en flytt dit kostar mer på flera små punkter samtidigt (%s) än vad %s är värt."
                .formatted(joinSv(phrases), wishName);
    }

    /** M-E2 review fix (MAJOR, ratio honesty): floor-based bands over the RAW ratio (never rounded to
     * the nearest integer first, which used to inflate e.g. a real 2.53x into "tre gånger så tungt
     * som" - three times as heavy). Boundaries per this milestone's brief, including the worked
     * example 2400/950 ≈ 2.53 landing in the 2.5–3.5 band. */
    private static String ratioWord(double ratio) {
        if (ratio < 0.75) {
            return "något mindre än";
        }
        if (ratio < 1.5) {
            return "ungefär lika tungt som";
        }
        if (ratio < 2.5) {
            return "ungefär dubbelt så tungt som";
        }
        if (ratio < 3.5) {
            return "drygt dubbelt så tungt som";
        }
        if (ratio < 4.5) {
            return "ungefär tre gånger så tungt som";
        }
        return "mycket tyngre än";
    }

    // ─────────────────────────────────────────────────────────────────────── per-pair / per-key text helpers

    private static boolean isSameGroupFamily(String key) {
        return ConstraintKeys.SAME_GROUP_HARD.equals(key) || ConstraintKeys.SAME_GROUP_SOFT.equals(key);
    }

    private static boolean isDifferentGroupFamily(String key) {
        return ConstraintKeys.DIFFERENT_GROUP_HARD.equals(key) || ConstraintKeys.DIFFERENT_GROUP_SOFT.equals(key);
    }

    /** MINOR review fix ("narrative-form reason labels instead of registry jargon"): a short, natural
     * Swedish phrase for ONE broken match ("kompisönskemålet med Lisa Larsson" rather than the
     * registry's "Samma grupp (mjuk)") when the match's justification names exactly one other
     * participant; falls back to {@link ConstraintMetadata}'s registry label for every key this
     * helper doesn't know a narrative form for (unknown/non-pair keys), per the brief's own fallback
     * rule. */
    private static String phraseFor(RunContext ctx, String key, List<MoveProbe.ScoredMatch> group, long targetId) {
        List<Long> others = distinctOtherParticipantIds(group, targetId);
        if (others.isEmpty()) {
            return ConstraintMetadata.of(key).label();
        }
        if (others.size() == 1) {
            String otherName = ctx.index().participantName(others.get(0));
            if (isSameGroupFamily(key)) {
                return "kompisönskemålet med " + otherName;
            }
            if (isDifferentGroupFamily(key)) {
                return "önskemålet om att undvika " + otherName;
            }
            return ConstraintMetadata.of(key).label();
        }
        return multiPairPhrase(ctx, key, others);
    }

    /** M-E2 review fix (MAJOR, per-PAIR granularity): when a single competing constraint KEY actually
     * aggregates MULTIPLE distinct broken pairs (e.g. moving away breaks the friendship with BOTH
     * Lisa and Moa), the message must say so explicitly ("2 kompisönskemål (med Lisa och Moa)")
     * instead of silently attaching ONE pair's specific message/label to the summed cost of both. */
    private static String multiPairPhrase(RunContext ctx, String key, List<Long> others) {
        List<String> names = others.stream().map(id -> ctx.index().participantName(id)).toList();
        String familyPlural = isSameGroupFamily(key)
                ? "kompisönskemål"
                : isDifferentGroupFamily(key) ? "önskemål om olika grupper" : ConstraintMetadata.of(key).label();
        return "%d %s (med %s)".formatted(others.size(), familyPlural, joinSv(names));
    }

    private static String messageForGroup(RunContext ctx, String key, List<MoveProbe.ScoredMatch> group, long targetId) {
        List<Long> others = distinctOtherParticipantIds(group, targetId);
        if (others.size() <= 1) {
            return group.get(0).messageSv();
        }
        return multiPairPhrase(ctx, key, others);
    }

    private static List<Long> distinctOtherParticipantIds(List<MoveProbe.ScoredMatch> group, long targetId) {
        LinkedHashSet<Long> others = new LinkedHashSet<>();
        for (MoveProbe.ScoredMatch m : group) {
            for (Long id : m.participantIds()) {
                if (id != targetId) {
                    others.add(id);
                }
            }
        }
        return new ArrayList<>(others);
    }

    // ─────────────────────────────────────────────────────────────────────── shared text helpers

    private static String sameAppliesClause(List<Group> others) {
        if (others.isEmpty()) {
            return "";
        }
        List<Group> named = others.size() > 2 ? others.subList(0, 2) : others;
        int remaining = others.size() - named.size();
        List<String> items = new ArrayList<>(named.stream().map(Group::name).toList());
        if (remaining > 0) {
            items.add(remaining == 1 ? "1 grupp till" : "%d grupper till".formatted(remaining));
        }
        return " Samma sak gäller %s.".formatted(joinSv(items));
    }

    /** MINOR review fix ("Swedish list comma before final 'och'"): 1 item as-is; 2 items joined with
     * a plain " och "; 3+ items comma-joined with an EXTRA comma before the final "och" (e.g. "A, B,
     * och C") — the exact convention this milestone's brief asks for. */
    private static String joinSv(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " och " + items.get(1);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + ", och " + items.get(items.size() - 1);
    }

    private static String hedgeSv(PlayerAssignment target) {
        return "Jämförelsen gäller att flytta %s ensam, med planen i övrigt oförändrad.".formatted(target.getDisplayName());
    }

    // ─────────────────────────────────────────────────────────────────────── wish text

    /** Package-visible (M-E3): {@code ExplanationService}'s {@code wish-analysis} endpoint needs to
     * find the ONE {@link UnmetWish} matching a caller-supplied {@code wishId} string, using the exact
     * same id scheme this class already uses for every {@code UnmetWishView.wishId()}. */
    static String wishId(UnmetWish wish) {
        return switch (wish.wishKind()) {
            case "FRIEND" -> "FRIEND:" + wish.otherParticipantSolverId();
            case "AVOID" -> "AVOID:" + wish.otherParticipantSolverId();
            case "COACH" -> "COACH:" + wish.coachPersonSolverId();
            default -> wish.wishKind();
        };
    }

    /** M-E2 review fix (BLOCKER, directed-wish attribution): {@code FRIEND}/{@code AVOID} wishes are
     * DIRECTED ({@link se.klubb.groupplanner.solver.domain.PersonPairWish#aParticipantProfileId()} is
     * the field owner) - when {@code target} is only the wish's TARGET, not its owner, this must never
     * read "«Namn» vill helst/måste ..." (that claims a desire {@code target} never expressed) and
     * instead attributes the wish to its actual owner: "«Ägare»s önskemål att spela med «Namn»". */
    private static String wishSv(RunContext ctx, PlayerAssignment target, UnmetWish wish) {
        return switch (wish.wishKind()) {
            case "TIME" -> "%s vill helst träna %s".formatted(target.getDisplayName(), preferredTimeLabels(ctx, target));
            case "FRIEND" -> {
                String other = ctx.index().participantName(wish.otherParticipantSolverId());
                if (wish.wishOwnedByTarget()) {
                    String verb = ConstraintKeys.SAME_GROUP_HARD.equals(wish.key()) ? "måste" : "vill helst";
                    yield "%s %s spela med %s".formatted(target.getDisplayName(), verb, other);
                }
                yield "%ss önskemål att spela med %s".formatted(other, target.getDisplayName());
            }
            case "AVOID" -> {
                String other = ctx.index().participantName(wish.otherParticipantSolverId());
                if (wish.wishOwnedByTarget()) {
                    String verb = ConstraintKeys.DIFFERENT_GROUP_HARD.equals(wish.key()) ? "måste undvika" : "vill helst undvika";
                    yield "%s %s att spela med %s".formatted(target.getDisplayName(), verb, other);
                }
                yield "%ss önskemål att undvika %s".formatted(other, target.getDisplayName());
            }
            case "PREVGROUP" -> "%s vill helst tillbaka till sin tidigare grupp".formatted(target.getDisplayName());
            case "COACH" -> {
                String coach = ctx.index().personName(wish.coachPersonSolverId());
                String verb = ConstraintKeys.COACH_WISH_REQUIRED.equals(wish.key()) ? "måste ha" : "vill helst ha";
                yield "%s %s tränare %s".formatted(target.getDisplayName(), verb, coach);
            }
            default -> target.getDisplayName() + " har ett önskemål som inte uppfylldes";
        };
    }

    private static String wishNameSv(RunContext ctx, UnmetWish wish) {
        return switch (wish.wishKind()) {
            case "TIME" -> "tidsönskemålet";
            case "FRIEND" -> "kompisönskemålet med %s".formatted(ctx.index().participantName(wish.otherParticipantSolverId()));
            case "AVOID" -> "önskemålet om att undvika %s".formatted(ctx.index().participantName(wish.otherParticipantSolverId()));
            case "PREVGROUP" -> "önskemålet om tidigare grupp";
            case "COACH" -> "tränarönskemålet";
            default -> "önskemålet";
        };
    }

    private static String preferredTimeLabels(RunContext ctx, PlayerAssignment target) {
        List<String> labels = new ArrayList<>();
        for (long slotId : target.getPreferredTimeSlotIds()) {
            labels.add(ctx.index().timeSlotLabel(slotId));
        }
        return String.join(" eller ", labels);
    }

    private static String timeLabel(ExplanationService svc, RunContext ctx, Group group) {
        String label = svc.groupTimeLabelOrNull(ctx, group);
        return label == null ? "okänd tid" : label;
    }

    // ─────────────────────────────────────────────────────────────────────── shared helpers

    private static MoveProbe.Result requireResult(Map<Group, MoveProbe.Result> probesByGroup, Group group) {
        MoveProbe.Result r = probesByGroup.get(group);
        if (r == null) {
            throw new IllegalStateException("No probe result for candidate group " + group.name() + " - E1's buildAlternatives "
                    + "must probe every group other than the selected one.");
        }
        return r;
    }

    /** M-E2 review fix (BLOCKER, per-PAIR granularity): sums ONLY the matches that concern this
     * wish's SPECIFIC pair (both {@code targetId} and {@code otherId} named by the justification) —
     * or, for wishes with no "other participant" (TIME/PREVGROUP/COACH), matches that at least name
     * {@code targetId} themselves when the justification carries participant identity at all. A
     * candidate that happens to fix a DIFFERENT pair sharing the SAME constraint key must never
     * inflate this wish's self-check gain. */
    private static long sumAbsPrimaryForPair(List<MoveProbe.ScoredMatch> matches, String key, long targetId, Long otherId) {
        long sum = 0L;
        for (MoveProbe.ScoredMatch m : matches) {
            if (!key.equals(m.key())) {
                continue;
            }
            if (otherId != null) {
                if (!involvesPair(m.participantIds(), targetId, otherId)) {
                    continue;
                }
            } else if (!m.participantIds().isEmpty() && !m.participantIds().contains(targetId)) {
                continue;
            }
            sum += Math.abs(primaryComponent(m.matchScore()));
        }
        return sum;
    }

    private static boolean involvesPair(List<Long> participantIds, long a, long b) {
        return participantIds.contains(a) && participantIds.contains(b);
    }

    /** M-E2 review fix (BLOCKER, per-PAIR granularity): groups {@code matches} by constraint key,
     * EXCLUDING any match that is exactly the wish's own pair+key (defensive - a match cannot
     * simultaneously be newly-fixed and newly-broken, but this keeps the invariant explicit and
     * future-proof rather than assumed). */
    private static Map<String, List<MoveProbe.ScoredMatch>> groupBrokenByKeyExcludingOwnPair(
            List<MoveProbe.ScoredMatch> matches, String wishKey, long targetId, Long otherId) {
        Map<String, List<MoveProbe.ScoredMatch>> byKey = new LinkedHashMap<>();
        for (MoveProbe.ScoredMatch m : matches) {
            if (otherId != null && wishKey.equals(m.key()) && involvesPair(m.participantIds(), targetId, otherId)) {
                continue; // the wish's own pair must never double as a "competing" reason against itself.
            }
            byKey.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(m);
        }
        return byKey;
    }

    private static List<Map.Entry<String, Long>> rankedEntries(Map<String, Long> byKey) {
        return byKey.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .toList();
    }

    private static long primaryComponent(HardMediumSoftLongScore score) {
        if (score.hardScore() != 0) {
            return score.hardScore();
        }
        if (score.mediumScore() != 0) {
            return score.mediumScore();
        }
        return score.softScore();
    }

    private static UnmetWishView view(
            String wishId, String key, String bucket, String wishSv, String outcome, String primaryReasonSv, String hedgeSv,
            List<String> candidateGroupIds, String bestCandidateGroupId, ScoreDeltaView bestCandidateDelta,
            List<ConstraintReasonView> competingReasons, PrioritySensitivityView sensitivity) {
        return new UnmetWishView(
                wishId, key, bucket, wishSv, outcome, primaryReasonSv, hedgeSv, candidateGroupIds, bestCandidateGroupId,
                bestCandidateDelta, competingReasons, sensitivity);
    }
}
