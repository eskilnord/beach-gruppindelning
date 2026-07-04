package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintMessageView;
import se.klubb.groupplanner.explain.ExplanationDtos.ImprovementSuggestionsResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.SuggestionView;
import se.klubb.groupplanner.explain.ExplanationService.RunContext;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.constraints.Justifications;
import se.klubb.groupplanner.solver.constraints.LevelMath;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * "Förbättringsförslag" — post-solve low-hanging-fruit suggestions (WI-D, user feedback v0.4 #2):
 * tells the council which SMALL data changes (one coach's availability, one group's max size by 1)
 * would unlock a placement that is currently hard-blocked by exactly that one thing. Built entirely
 * on top of the M7 explainability machinery ({@link ExplanationService#loadContext}/{@link
 * MoveProbe}) — every suggestion is a probed, data-derived fact (never a guess), matching the same
 * truthfulness discipline as {@link ExplanationService} itself (kravspec §17.4).
 *
 * <p>Three suggestion families, tried in priority order (A, then B, then C — the order the final
 * response list is built in):
 *
 * <ol>
 *   <li><b>A — waitlisted players:</b> for each waitlisted {@code PlayerAssignment}, probe every
 *       group; if the BEST candidate's only hard blocker is exactly one class ({@code
 *       timeAvailabilityHard} or {@code groupMaxSizeHard}), suggest fixing that one thing. Multiple
 *       players blocked by the same group's max size are merged into ONE suggestion (kind {@code
 *       GROUP_MAX}) naming the count.
 *   <li><b>B — coachless groups:</b> for each group with an empty required {@code CoachSlot}, look
 *       for a level-fitting, non-overlapping coach — one not hard-blocked in any way the suggested
 *       data change couldn't fix: no coaching overlap, not also PLAYING at an overlapping time
 *       (§10.17), not forbidden by a current member's {@code CANNOT} wish (§10.21c) — who is either
 *       explicitly unavailable at the group's slot ({@code COACH_TIME}) or already at their {@code
 *       maxGroups} cap ({@code COACH_MAX}) — at most 2 candidates per group, best level fit first.
 *   <li><b>C — broken WANT_SAME wishes:</b> for each broken {@code sameGroupSoft} match, probe
 *       moving either side into the other's group; a sole {@code groupMaxSizeHard} blocker becomes
 *       {@code GROUP_MAX_WISH} (deduped into an existing group-A {@code GROUP_MAX} suggestion for
 *       the SAME group, if any — a group only ever gets one max-size suggestion), a sole {@code
 *       timeAvailabilityHard} blocker becomes {@code PLAYER_TIME_WISH}.
 * </ol>
 *
 * <p>The response is capped at {@link #MAX_SUGGESTIONS} entries ({@code omittedCount} carries the
 * drop count) and cached per {@code (runId, planRevision)} via {@link ImprovementSuggestionCache} —
 * see that class's javadoc for why it is a parallel cache rather than a reuse of {@link
 * ExplanationCache}. Every list this class builds is iterated in a stable, DB-id-derived order
 * (CLAUDE.md determinism rule): the same run always yields the same suggestion list.
 *
 * <p>Confidentiality: this class never reads {@code importedComment}/{@code internalNote} — every
 * fact it touches ({@code PlayerAssignment}, {@code CoachFact}, {@code Group}, {@code
 * ConstraintJustification}) is a solver-domain problem fact that never carries those fields in the
 * first place (see {@code SolverInputAssembler}).
 */
@Service
public class ImprovementSuggestionService {

    /** Response-level cap (task brief: "Cap the response at 10 suggestions"). */
    private static final int MAX_SUGGESTIONS = 10;

    /** Per-coachless-group cap on B-kind candidates (task brief: "at most 2 coach candidates per
     *  group"). */
    private static final int MAX_COACH_CANDIDATES_PER_GROUP = 2;

    private final ExplanationService explanationService;
    private final MoveProbe moveProbe;
    private final ImprovementSuggestionCache cache;
    private final TrainingBlockRepository trainingBlockRepository;

    public ImprovementSuggestionService(
            ExplanationService explanationService,
            MoveProbe moveProbe,
            ImprovementSuggestionCache cache,
            TrainingBlockRepository trainingBlockRepository) {
        this.explanationService = explanationService;
        this.moveProbe = moveProbe;
        this.cache = cache;
        this.trainingBlockRepository = trainingBlockRepository;
    }

    public ImprovementSuggestionsResponse suggestions(String planId, String runId) {
        RunContext ctx = explanationService.loadContext(planId, runId);

        ImprovementSuggestionCache.Key cacheKey = new ImprovementSuggestionCache.Key(runId, ctx.currentRevision());
        ImprovementSuggestionsResponse cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<Long, GroupSchedule> scheduleByGroupId = new LinkedHashMap<>();
        for (GroupSchedule gs : ctx.solution().getGroupSchedules()) {
            scheduleByGroupId.put(gs.getGroup().id(), gs);
        }

        // Shared between A and C so a group's GROUP_MAX suggestion is deduped/merged regardless of
        // which family discovered it first (task brief: "Dedupe with A's GROUP_MAX suggestions on
        // the same group ... merge impact texts"). `Object` entries are either a finished {@link
        // SuggestionView} or a still-mutable {@link GroupMaxAggregate}, resolved to a view only once
        // ALL families have finished mutating it (see #resolve).
        Map<Long, GroupMaxAggregate> groupMaxAggregates = new LinkedHashMap<>();

        List<Object> aRaw = new ArrayList<>();
        buildWaitlistSuggestions(ctx, scheduleByGroupId, aRaw, groupMaxAggregates);

        List<SuggestionView> bItems = buildCoachlessSuggestions(ctx, scheduleByGroupId);

        List<Object> cRaw = new ArrayList<>();
        buildBrokenWishSuggestions(ctx, scheduleByGroupId, groupMaxAggregates, cRaw);

        List<SuggestionView> all = new ArrayList<>();
        for (Object item : aRaw) {
            all.add(resolve(ctx, item));
        }
        all.addAll(bItems);
        for (Object item : cRaw) {
            all.add(resolve(ctx, item));
        }

        int omittedCount = Math.max(0, all.size() - MAX_SUGGESTIONS);
        List<SuggestionView> capped = all.size() > MAX_SUGGESTIONS ? List.copyOf(all.subList(0, MAX_SUGGESTIONS)) : List.copyOf(all);

        ImprovementSuggestionsResponse response = new ImprovementSuggestionsResponse(
                ctx.run().id(), ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(), capped, omittedCount);
        cache.put(cacheKey, response);
        return response;
    }

    private SuggestionView resolve(RunContext ctx, Object item) {
        return item instanceof SuggestionView v ? v : toSuggestionView(ctx, (GroupMaxAggregate) item);
    }

    // ─────────────────────────────────────────────────────────────────────── A: waitlisted players

    /** For each waitlisted player (deterministic ascending solver-id order), probes every group
     *  (deterministic ascending {@code groupOrder}) and keeps only the BEST candidate whose sole
     *  hard blocker is a class this service knows how to phrase a suggestion for. "Best" = smallest
     *  magnitude hard-break, tiebroken by the probe's own score delta (natural {@code Score}
     *  ordering, better first), tiebroken by group order — a total, deterministic order. */
    private void buildWaitlistSuggestions(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, List<Object> aRaw,
            Map<Long, GroupMaxAggregate> groupMaxAggregates) {
        List<PlayerAssignment> waitlisted = ctx.solution().getPlayerAssignments().stream()
                .filter(pa -> pa.getGroup() == null)
                .sorted(Comparator.comparingLong(PlayerAssignment::getId))
                .toList();
        List<Group> allGroups = ctx.solution().getGroups().stream()
                .sorted(Comparator.comparingInt(Group::groupOrder))
                .toList();

        for (PlayerAssignment player : waitlisted) {
            Group bestGroup = null;
            MoveProbe.Result bestResult = null;
            String bestKey = null;
            for (Group g : allGroups) {
                MoveProbe.Result r = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), player, g, ctx.index());
                String key = soleActionableHardKey(r);
                if (key == null) {
                    continue;
                }
                if (bestResult == null || isBetterCandidate(r, g, bestResult, bestGroup)) {
                    bestGroup = g;
                    bestResult = r;
                    bestKey = key;
                }
            }
            if (bestGroup == null) {
                continue;
            }
            if (ConstraintKeys.TIME_AVAILABILITY_HARD.equals(bestKey)) {
                aRaw.add(buildPlayerTimeSuggestion(ctx, scheduleByGroupId, player, bestGroup));
            } else {
                GroupMaxAggregate agg = groupMaxAggregates.get(bestGroup.id());
                if (agg == null) {
                    agg = new GroupMaxAggregate(bestGroup);
                    groupMaxAggregates.put(bestGroup.id(), agg);
                    aRaw.add(agg);
                }
                agg.waitlistedPlayers.add(player);
            }
        }
    }

    private static boolean isBetterCandidate(MoveProbe.Result r, Group g, MoveProbe.Result best, Group bestGroup) {
        long rAbsHard = Math.abs(r.scoreDelta().hardScore());
        long bestAbsHard = Math.abs(best.scoreDelta().hardScore());
        if (rAbsHard != bestAbsHard) {
            return rAbsHard < bestAbsHard;
        }
        int scoreCmp = r.scoreDelta().compareTo(best.scoreDelta());
        if (scoreCmp != 0) {
            return scoreCmp > 0;
        }
        return g.groupOrder() < bestGroup.groupOrder();
    }

    private SuggestionView buildPlayerTimeSuggestion(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, PlayerAssignment player, Group group) {
        Long timeSlotId = timeSlotIdOf(scheduleByGroupId, group);
        String timeLabel = timeSlotId == null ? "" : ctx.index().timeSlotLabel(timeSlotId);
        String title = "Om %s kunde träna %s skulle hen få plats i %s.".formatted(player.getDisplayName(), timeLabel, group.name());
        return new SuggestionView(
                "PLAYER_TIME", title, null, "1 spelare färre på kölistan",
                explanationService.groupDbId(ctx, group), explanationService.participantDbId(ctx, player.getId()), null,
                timeSlotDbIdFor(ctx, scheduleByGroupId, group));
    }

    /** Mutable accumulator for a "raise this group's max size" suggestion — merges BOTH the
     *  waitlisted players it would unblock (family A) and the broken WANT_SAME wish pairs it would
     *  unblock (family C, see {@link #buildBrokenWishSuggestions}) into a SINGLE {@link
     *  SuggestionView} per group (task brief: "a group gets ONE max-size suggestion"), whose asked-
     *  for increase is sized to fit every merged beneficiary (see {@link #toSuggestionView}). */
    private static final class GroupMaxAggregate {
        final Group group;
        final List<PlayerAssignment> waitlistedPlayers = new ArrayList<>();
        final List<String[]> wishPairs = new ArrayList<>();

        GroupMaxAggregate(Group group) {
            this.group = group;
        }
    }

    private SuggestionView toSuggestionView(RunContext ctx, GroupMaxAggregate agg) {
        String groupDbId = explanationService.groupDbId(ctx, agg.group);
        int n = agg.waitlistedPlayers.size();
        int k = agg.wishPairs.size();
        // Opus review FIX 1 (truthfulness, same class as M7 review fix M1): groupMaxSizeHard being
        // the SOLE blocker of every merged beneficiary means the group is EXACTLY at max - a merged
        // suggestion covering N waitlisted players and K wish pairs (each pair's moving partner needs
        // one seat) must name EVERY one of the N+K beneficiaries it claims to cover (n/k below), never
        // imply a single beneficiary when there is more than one.
        String kind;
        String title;
        String impact;
        String participantId = null;
        // User feedback v0.4.1: a group's max size is a HARD constraint (court capacity) the council
        // cannot actually change from this screen - phrasing it as an imperative ("Öka maxstorleken
        // ...") reads as an actionable suggestion when it isn't one. GROUP_MAX/GROUP_MAX_WISH are now
        // phrased as an EXPLANATION of the limitation ("Grupp X är full (max N)") plus what that
        // fullness costs, not a call to action - the beneficiary aggregation (Opus review FIX 1,
        // above) is untouched; the old "+N seats" claim went away with the imperative wording.
        if (n > 0) {
            kind = "GROUP_MAX";
            StringBuilder tail = new StringBuilder();
            if (n == 1) {
                PlayerAssignment only = agg.waitlistedPlayers.get(0);
                tail.append("det hindrar %s från en plats".formatted(only.getDisplayName()));
                participantId = explanationService.participantDbId(ctx, only.getId());
            } else {
                tail.append("det hindrar %d spelare från platser".formatted(n));
            }
            if (k > 0) {
                // "samt" rather than a second "och hindrar" - the tail already chains "X och Y" pair
                // names, so a plain "och" would stack three "och":s in one sentence.
                tail.append(k == 1
                        ? " samt %s och %s från att spela ihop".formatted(agg.wishPairs.get(0)[0], agg.wishPairs.get(0)[1])
                        : " samt %d spelpar från att spela ihop".formatted(k));
            }
            title = "Grupp %s är full (max %d) – %s.".formatted(agg.group.name(), agg.group.maxSize(), tail);
            impact = n == 1 ? "hindrar 1 spelare från en plats" : "hindrar %d spelare från platser".formatted(n);
            if (k > 0) {
                impact += k == 1
                        ? "; hindrar %s och %s från att spela ihop".formatted(agg.wishPairs.get(0)[0], agg.wishPairs.get(0)[1])
                        : "; hindrar %d spelpar från att spela ihop".formatted(k);
            }
        } else {
            kind = "GROUP_MAX_WISH";
            if (k == 1) {
                String[] pair = agg.wishPairs.get(0);
                title = "Grupp %s är full (max %d) – det hindrar %s och %s från att spela ihop.".formatted(
                        agg.group.name(), agg.group.maxSize(), pair[0], pair[1]);
            } else {
                title = "Grupp %s är full (max %d) – det hindrar %d spelpar från att spela ihop.".formatted(
                        agg.group.name(), agg.group.maxSize(), k);
            }
            impact = k == 1 ? "hindrar 1 spelpar från att spela ihop" : "hindrar %d spelpar från att spela ihop".formatted(k);
        }
        String detail = n > 1
                ? "Berörda spelare: %s.".formatted(String.join(", ", agg.waitlistedPlayers.stream().map(PlayerAssignment::getDisplayName).toList()))
                : null;
        return new SuggestionView(kind, title, detail, impact, groupDbId, participantId, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────── B: coachless groups

    /** For each group with at least one empty required {@code CoachSlot} (deduped, ascending {@code
     *  groupOrder}), scans every coach for one of two fix shapes — see class javadoc families B.
     *  Level-fit and overlap use the SAME integer math as {@code GroupPlanConstraintProvider
     *  .coachLevelFit}/{@code coachNoOverlap} (replicated here rather than called directly: both are
     *  {@code private static} helpers on a different package's constraint provider operating on its
     *  own package-private {@code GroupLevelStat} record, so a literal call is not available without
     *  widening that class's API — replicating the small integer formula is the lower-risk choice, no
     *  behavior of the constraint provider itself is touched). */
    private List<SuggestionView> buildCoachlessSuggestions(RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId) {
        List<SuggestionView> out = new ArrayList<>();

        Set<Long> seenGroupIds = new LinkedHashSet<>();
        List<Group> coachlessGroups = new ArrayList<>();
        for (CoachSlot slot : ctx.solution().getCoachSlots()) {
            if (slot.getCoach() == null && seenGroupIds.add(slot.getGroup().id())) {
                coachlessGroups.add(slot.getGroup());
            }
        }
        coachlessGroups.sort(Comparator.comparingInt(Group::groupOrder));

        List<CoachFact> allCoaches = new ArrayList<>(ctx.solution().getCoaches());
        allCoaches.sort(Comparator.comparingLong(CoachFact::coachProfileId));

        for (Group group : coachlessGroups) {
            GroupSchedule gs = scheduleByGroupId.get(group.id());
            if (gs == null || gs.getTrainingBlock() == null) {
                continue; // no assigned time/court at all - nothing to reason about yet.
            }
            long timeSlotId = gs.getTrainingBlock().timeSlotId();
            TimeKey targetTimeKey = gs.getTrainingBlock().timeKey();
            long sum = 0L;
            int count = 0;
            for (PlayerAssignment pa : ctx.solution().getPlayerAssignments()) {
                if (pa.getGroup() == group) {
                    sum += pa.getLevelScaled();
                    count++;
                }
            }

            List<CoachFact> tier1 = new ArrayList<>();
            List<CoachFact> tier2 = new ArrayList<>();
            for (CoachFact coach : allCoaches) {
                if (!levelFits(coach, sum, count)) {
                    continue;
                }
                if (!noOverlapWithExisting(ctx, scheduleByGroupId, coach, targetTimeKey, group)) {
                    continue;
                }
                // Opus review FIX 2: two further HARD constraints the suggested assignment must not
                // trip - the availability change being suggested cannot fix either of these.
                if (wouldTrainAndCoachSameTime(ctx, scheduleByGroupId, coach, targetTimeKey)) {
                    continue; // coachCannotTrainAndCoachSameTime (§10.17): coach also PLAYS at an overlapping time.
                }
                if (forbiddenByGroupMember(ctx, coach, group)) {
                    continue; // coachWishForbidden (§10.21c): a current member of the group forbids this coach.
                }
                int assigned = assignedGroupCount(ctx, coach);
                boolean unavailableAtSlot = !coach.availableAt(timeSlotId);
                if (unavailableAtSlot && assigned < coach.maxGroups()) {
                    tier1.add(coach);
                } else if (!unavailableAtSlot && assigned >= coach.maxGroups()) {
                    tier2.add(coach);
                }
            }

            boolean isCoachTime = !tier1.isEmpty();
            List<CoachFact> winners = isCoachTime ? tier1 : tier2;
            winners.sort(bestLevelFitComparator(sum, count));

            String groupDbId = explanationService.groupDbId(ctx, group);
            String timeSlotDbId = timeSlotDbIdFor(ctx, scheduleByGroupId, group);
            String timeLabel = ctx.index().timeSlotLabel(timeSlotId);

            int emitted = 0;
            for (CoachFact coach : winners) {
                if (emitted >= MAX_COACH_CANDIDATES_PER_GROUP) {
                    break;
                }
                String coachDbId = ctx.assembled().coachProfileDbIdByLongId().get(coach.coachProfileId());
                if (isCoachTime) {
                    out.add(new SuggestionView(
                            "COACH_TIME",
                            "Om %s kunde ta %s skulle %s få en tränare.".formatted(coach.displayName(), timeLabel, group.name()),
                            null, "1 grupp utan tränare åtgärdas", groupDbId, null, coachDbId, timeSlotDbId));
                } else {
                    out.add(new SuggestionView(
                            "COACH_MAX",
                            "Om %s kunde ta fler grupper (max nu %d) skulle %s få en tränare.".formatted(
                                    coach.displayName(), coach.maxGroups(), group.name()),
                            null, "1 grupp utan tränare åtgärdas", groupDbId, null, coachDbId, timeSlotDbId));
                }
                emitted++;
            }
        }
        return out;
    }

    /** Same fixed-point in/out-of-band check as {@code GroupPlanConstraintProvider
     *  .outsideCoachBand} (negated): an EMPTY group (count == 0) trivially fits — {@code
     *  groupLevelStats} never produces a tuple for an empty group, so {@code coachLevelFit} is inert
     *  for it by construction (see that class's javadoc), and this mirrors the same blindness. */
    private static boolean levelFits(CoachFact coach, long sumScaled, int count) {
        if (count == 0) {
            return true;
        }
        return !(sumScaled < (long) coach.canCoachMinScaled() * count || sumScaled > (long) coach.canCoachMaxScaled() * count);
    }

    private static boolean noOverlapWithExisting(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, CoachFact coach, TimeKey targetTimeKey, Group targetGroup) {
        for (CoachSlot slot : ctx.solution().getCoachSlots()) {
            if (slot.getCoach() == null || slot.getCoach().coachProfileId() != coach.coachProfileId()) {
                continue;
            }
            if (slot.getGroup() == targetGroup) {
                continue;
            }
            GroupSchedule gs = scheduleByGroupId.get(slot.getGroup().id());
            if (gs == null || gs.getTrainingBlock() == null) {
                continue;
            }
            if (gs.getTrainingBlock().timeKey().overlaps(targetTimeKey)) {
                return false;
            }
        }
        return true;
    }

    /** Opus review FIX 2a — mirror of {@code GroupPlanConstraintProvider
     *  .coachCannotTrainAndCoachSameTime} (§10.17): the candidate coach may ALSO be a participant;
     *  if any {@code PlayerAssignment} sharing their {@code personId} is placed in a group whose
     *  block OVERLAPS the coachless group's block ({@link TimeKey#overlaps}, the exact same
     *  semantics the constraint's own {@code timeKey().overlaps(...)} filter uses), assigning them
     *  as coach would trade one HARD violation for another — never a suggestion. A player in the
     *  TARGET group itself trivially overlaps (a block overlaps itself) and is correctly excluded:
     *  one person cannot play in and coach the same session. */
    private static boolean wouldTrainAndCoachSameTime(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, CoachFact coach, TimeKey targetTimeKey) {
        for (PlayerAssignment pa : ctx.solution().getPlayerAssignments()) {
            if (pa.getPersonId() != coach.personId() || pa.getGroup() == null) {
                continue;
            }
            GroupSchedule gs = scheduleByGroupId.get(pa.getGroup().id());
            if (gs == null || gs.getTrainingBlock() == null) {
                continue;
            }
            if (gs.getTrainingBlock().timeKey().overlaps(targetTimeKey)) {
                return true;
            }
        }
        return false;
    }

    /** Opus review FIX 2b — mirror of {@code GroupPlanConstraintProvider.coachWishForbidden}
     *  (§10.21c): a {@code CANNOT} coach wish held by any player CURRENTLY placed in the target
     *  group against the candidate coach's person makes the suggested assignment a HARD violation
     *  (the constraint joins wish -&gt; placed participant -&gt; the group's CoachSlot carrying the
     *  forbidden coach's personId) — never a suggestion. */
    private static boolean forbiddenByGroupMember(RunContext ctx, CoachFact coach, Group targetGroup) {
        for (CoachWish wish : ctx.solution().getCoachWishes()) {
            if (wish.type() != CoachWishType.CANNOT || wish.coachPersonId() != coach.personId()) {
                continue;
            }
            PlayerAssignment pa = ctx.index().player(wish.participantProfileId());
            if (pa != null && pa.getGroup() == targetGroup) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors {@code coachMaxGroups}' own unit of measure: a COUNT OF {@code CoachSlot}s currently
     *  resolving to this coach (not distinct groups — see that constraint's {@code groupBy(CoachSlot
     *  ::getCoach, count())}), so "spare capacity" here means exactly what the solver enforces. */
    private static int assignedGroupCount(RunContext ctx, CoachFact coach) {
        int n = 0;
        for (CoachSlot slot : ctx.solution().getCoachSlots()) {
            if (slot.getCoach() != null && slot.getCoach().coachProfileId() == coach.coachProfileId()) {
                n++;
            }
        }
        return n;
    }

    /** "Best level fit first" (task brief): ranked by how comfortably the coach's band contains the
     *  group's current mean level (larger margin to the nearer band edge = more comfortable),
     *  descending; ties broken by {@code coachProfileId} ascending for a total deterministic order.
     *  An empty group (no meaningful mean) ranks every candidate equally on fit, falling through to
     *  the id tiebreak. */
    private static Comparator<CoachFact> bestLevelFitComparator(long sumScaled, int count) {
        return Comparator
                .comparingLong((CoachFact c) -> -marginFor(c, sumScaled, count))
                .thenComparingLong(CoachFact::coachProfileId);
    }

    private static long marginFor(CoachFact coach, long sumScaled, int count) {
        if (count == 0) {
            return 0L;
        }
        long meanScaled = LevelMath.floorMean(sumScaled, count);
        long lower = meanScaled - coach.canCoachMinScaled();
        long upper = coach.canCoachMaxScaled() - meanScaled;
        return Math.min(lower, upper);
    }

    // ─────────────────────────────────────────────────────────────────────── C: broken WANT_SAME wishes

    /** For each broken {@code sameGroupSoft} (WANT_SAME) match in {@code baseAnalysis} — both sides
     *  are, by construction, PLACED in different groups (the constraint's {@code forEach
     *  (PlayerAssignment.class)} excludes waitlisted players entirely, see {@link
     *  ExplanationService#addWaitlistedFriendNotices}'s javadoc for the same join fact) — probes
     *  moving A into B's group and B into A's group, in that order, and takes the FIRST qualifying
     *  outcome: a sole {@code groupMaxSizeHard} blocker (either direction) before a sole {@code
     *  timeAvailabilityHard} blocker (either direction), so at most ONE suggestion is produced per
     *  broken pair. */
    private void buildBrokenWishSuggestions(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, Map<Long, GroupMaxAggregate> groupMaxAggregates,
            List<Object> cRaw) {
        List<MatchAnalysis<HardMediumSoftLongScore>> brokenMatches = new ArrayList<>();
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : ctx.baseAnalysis().constraintAnalyses()) {
            if (!ConstraintKeys.SAME_GROUP_SOFT.equals(ca.constraintName())) {
                continue;
            }
            for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                if (isNegative(match.score()) && match.justification() instanceof Justifications.PairWishSoftJustification) {
                    brokenMatches.add(match);
                }
            }
        }
        brokenMatches.sort(Comparator.comparingLong(
                (MatchAnalysis<HardMediumSoftLongScore> m) -> ((Justifications.PairWishSoftJustification) m.justification()).aParticipantId())
                .thenComparingLong(m -> ((Justifications.PairWishSoftJustification) m.justification()).bParticipantId()));

        for (MatchAnalysis<HardMediumSoftLongScore> match : brokenMatches) {
            Justifications.PairWishSoftJustification j = (Justifications.PairWishSoftJustification) match.justification();
            PlayerAssignment a = ctx.index().player(j.aParticipantId());
            PlayerAssignment b = ctx.index().player(j.bParticipantId());
            if (a == null || b == null || a.getGroup() == null || b.getGroup() == null) {
                continue; // defensive: should not happen per the join fact above.
            }
            Group groupA = a.getGroup();
            Group groupB = b.getGroup();
            MoveProbe.Result r1 = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), a, groupB, ctx.index());
            MoveProbe.Result r2 = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), b, groupA, ctx.index());

            if (tryGroupMaxWish(groupMaxAggregates, cRaw, r1, groupB, a, b)) {
                continue;
            }
            if (tryGroupMaxWish(groupMaxAggregates, cRaw, r2, groupA, b, a)) {
                continue;
            }
            if (tryTimeWish(ctx, scheduleByGroupId, cRaw, r1, groupB, a, b)) {
                continue;
            }
            tryTimeWish(ctx, scheduleByGroupId, cRaw, r2, groupA, b, a);
        }
    }

    private boolean tryGroupMaxWish(
            Map<Long, GroupMaxAggregate> groupMaxAggregates, List<Object> cRaw, MoveProbe.Result r, Group targetGroup,
            PlayerAssignment mover, PlayerAssignment stayer) {
        if (!isSoleHardBlocker(r, ConstraintKeys.GROUP_MAX_SIZE_HARD)) {
            return false;
        }
        GroupMaxAggregate agg = groupMaxAggregates.get(targetGroup.id());
        if (agg == null) {
            agg = new GroupMaxAggregate(targetGroup);
            groupMaxAggregates.put(targetGroup.id(), agg);
            cRaw.add(agg);
        }
        agg.wishPairs.add(new String[] {mover.getDisplayName(), stayer.getDisplayName()});
        return true;
    }

    private boolean tryTimeWish(
            RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, List<Object> cRaw, MoveProbe.Result r, Group targetGroup,
            PlayerAssignment mover, PlayerAssignment stayer) {
        if (!isSoleHardBlocker(r, ConstraintKeys.TIME_AVAILABILITY_HARD)) {
            return false;
        }
        Long timeSlotId = timeSlotIdOf(scheduleByGroupId, targetGroup);
        String timeLabel = timeSlotId == null ? "" : ctx.index().timeSlotLabel(timeSlotId);
        String title = "Om %s kunde träna %s skulle hen hamna med %s i %s.".formatted(
                mover.getDisplayName(), timeLabel, stayer.getDisplayName(), targetGroup.name());
        cRaw.add(new SuggestionView(
                "PLAYER_TIME_WISH", title, null, "1 spelpar kan spela ihop",
                explanationService.groupDbId(ctx, targetGroup), explanationService.participantDbId(ctx, mover.getId()), null,
                timeSlotDbIdFor(ctx, scheduleByGroupId, targetGroup)));
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────── shared helpers

    /** The single hard-blocker class of a move probe, restricted to the two classes this service
     *  knows how to phrase a suggestion for ({@code timeAvailabilityHard}/{@code groupMaxSizeHard}) —
     *  {@code null} whenever the probe is hard-feasible, blocked by more than one class, or blocked
     *  by a class outside that pair (task brief A: "EXACTLY ONE class"). */
    private static String soleActionableHardKey(MoveProbe.Result r) {
        String key = soleHardKey(r);
        if (key == null) {
            return null;
        }
        return (ConstraintKeys.TIME_AVAILABILITY_HARD.equals(key) || ConstraintKeys.GROUP_MAX_SIZE_HARD.equals(key)) ? key : null;
    }

    private static boolean isSoleHardBlocker(MoveProbe.Result r, String expectedKey) {
        return expectedKey.equals(soleHardKey(r));
    }

    /** The single distinct HARD-level newly-broken constraint key of a probe result, or {@code null}
     *  if the probe is hard-feasible or blocked by more than one distinct HARD class. {@code
     *  newlyBroken} itself may also carry non-HARD (medium/soft) negative matches - filtered out
     *  here since only a HARD class can be the reason {@code wouldBreakHard()} is true. */
    private static String soleHardKey(MoveProbe.Result r) {
        if (!r.wouldBreakHard()) {
            return null;
        }
        Set<String> hardKeys = new LinkedHashSet<>();
        for (ConstraintMessageView m : r.newlyBroken()) {
            if (HardOrSoft.HARD.equals(ConstraintMetadata.of(m.key()).level())) {
                hardKeys.add(m.key());
            }
        }
        return hardKeys.size() == 1 ? hardKeys.iterator().next() : null;
    }

    private static Long timeSlotIdOf(Map<Long, GroupSchedule> scheduleByGroupId, Group group) {
        GroupSchedule gs = scheduleByGroupId.get(group.id());
        return gs == null || gs.getTrainingBlock() == null ? null : gs.getTrainingBlock().timeSlotId();
    }

    /** Resolves the DB {@code time_slot.id} for a group's currently assigned block, via one {@code
     *  training_block} lookup (cheap: called a handful of times, once per suggestion-bearing group/
     *  wish pair, never per probe). {@code null} whenever the group has no assigned block or the
     *  lookup otherwise can't resolve — the response field is optional. */
    private String timeSlotDbIdFor(RunContext ctx, Map<Long, GroupSchedule> scheduleByGroupId, Group group) {
        GroupSchedule gs = scheduleByGroupId.get(group.id());
        if (gs == null || gs.getTrainingBlock() == null) {
            return null;
        }
        String blockDbId = ctx.assembled().trainingBlockDbIdByLongId().get(gs.getTrainingBlock().id());
        if (blockDbId == null) {
            return null;
        }
        return trainingBlockRepository.findById(blockDbId).map(se.klubb.groupplanner.domain.TrainingBlock::timeSlotId).orElse(null);
    }

    private static boolean isNegative(HardMediumSoftLongScore s) {
        if (s.hardScore() != 0) {
            return s.hardScore() < 0;
        }
        if (s.mediumScore() != 0) {
            return s.mediumScore() < 0;
        }
        return s.softScore() < 0;
    }
}
