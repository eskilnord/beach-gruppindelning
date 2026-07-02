package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.explain.ExplanationDtos.AlternativeGroupView;
import se.klubb.groupplanner.explain.ExplanationDtos.AppliedWeightView;
import se.klubb.groupplanner.explain.ExplanationDtos.BrokenWishView;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintMessageView;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintSummaryView;
import se.klubb.groupplanner.explain.ExplanationDtos.FactorView;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupBlockView;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupCoachView;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupMatchView;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupMemberBrokenWishView;
import se.klubb.groupplanner.explain.ExplanationDtos.HardViolationView;
import se.klubb.groupplanner.explain.ExplanationDtos.ManualReviewEntryView;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.PlanExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.ProblematicGroupView;
import se.klubb.groupplanner.explain.ExplanationDtos.ScoreDeltaView;
import se.klubb.groupplanner.explain.ExplanationDtos.SelectedGroupView;
import se.klubb.groupplanner.explain.ExplanationDtos.WaitlistBlockerView;
import se.klubb.groupplanner.explain.ExplanationDtos.WaitlistEntryView;
import se.klubb.groupplanner.explain.ExplanationDtos.WaitlistView;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ExplanationRecordRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.constraints.Justifications;
import se.klubb.groupplanner.solver.constraints.LevelMath;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * The explainability engine (task item 4, docs/design/04-solver.md §11, kravspec §17/§18 chapter,
 * amended by docs/design/05-solver-verification.md's waitlist/union-rule fixes). Three levels
 * (person/group/plan), all built from ONE re-analysis of the plan's CURRENT persisted state (never
 * from an LLM) — every sentence traces back to a {@link ConstraintJustification} or a direct data
 * comparison (level bands, group capacity, priority), per kravspec §17.4's "inte genom att en AI
 * gissar" requirement.
 *
 * <p>See backend/docs/m7-notes.md for the full design record, in particular: why explanations are
 * computed against CURRENT DB state rather than a true historical run snapshot (this codebase's
 * {@code SolverInputAssembler} has no "replay as of run R" capability — see {@code
 * se.klubb.groupplanner.solver.run.SolveCoordinator#persistResult}'s revision-bump for how staleness
 * still stays correct), and why {@link MoveProbe} mutates-and-restores instead of rebuilding a fresh
 * solution per probe (the &lt;1s latency gate).
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    /** Constraint keys always shown in a person's {@code appliedWeights} regardless of whether they
     * happened to fire a match for this specific player — every placement is implicitly subject to
     * these (design §17's own worked example: "Nivåbalans: soft 100, Gruppstorlek: hard, ..."). */
    private static final List<String> ALWAYS_RELEVANT_WEIGHT_KEYS = List.of(
            ConstraintKeys.GROUP_MAX_SIZE_HARD, ConstraintKeys.GROUP_SIZE_TARGET, ConstraintKeys.GROUP_MIN_SIZE_SOFT,
            ConstraintKeys.LEVEL_BALANCE, ConstraintKeys.GROUP_ORDER_BY_LEVEL, ConstraintKeys.UNASSIGNED_PLAYER);

    /** Wish-family keys whose PENALIZE matches count as "broken wishes" (vs. plain negative factors). */
    private static final Set<String> WISH_KEYS = Set.of(
            ConstraintKeys.SAME_GROUP_HARD, ConstraintKeys.SAME_GROUP_SOFT, ConstraintKeys.DIFFERENT_GROUP_HARD,
            ConstraintKeys.DIFFERENT_GROUP_SOFT, ConstraintKeys.COACH_WISH_REQUIRED, ConstraintKeys.COACH_WISH_FORBIDDEN,
            ConstraintKeys.COACH_PREFERENCE_SOFT);

    private static final List<String> SUGGESTED_ACTIONS = List.of("KEEP", "MOVE_ANYWAY", "LOCK_AND_RESOLVE");

    /** M7 review fix m7: "{runId}|{participantId}" keys whose solver-quality WARN has already been
     * logged — dedupes the log line across repeated requests/cache misses (the RESPONSE field is
     * always populated regardless; only the log noise is deduped). Bounded in practice by runs ×
     * waitlisted players per JVM lifetime — negligible for a desktop app; a ConcurrentHashMap-backed
     * set for thread-safety. */
    private final Set<String> solverQualityWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ActivityPlanRepository activityPlanRepository;
    private final OptimizationRunRepository optimizationRunRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PersonRepository personRepository;
    private final SolverInputAssembler assembler;
    private final SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager;
    private final MoveProbe moveProbe;
    private final ExplanationCache cache;
    private final ExplanationRecordRepository explanationRecordRepository;
    private final ObjectMapper objectMapper;

    public ExplanationService(
            ActivityPlanRepository activityPlanRepository,
            OptimizationRunRepository optimizationRunRepository,
            ParticipantProfileRepository participantProfileRepository,
            TrainingGroupRepository trainingGroupRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            PersonRepository personRepository,
            SolverInputAssembler assembler,
            SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager,
            MoveProbe moveProbe,
            ExplanationCache cache,
            ExplanationRecordRepository explanationRecordRepository,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.optimizationRunRepository = optimizationRunRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.personRepository = personRepository;
        this.assembler = assembler;
        this.solutionManager = solutionManager;
        this.moveProbe = moveProbe;
        this.cache = cache;
        this.explanationRecordRepository = explanationRecordRepository;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────── run context

    /** Everything every explain/what-if computation needs, loaded/analyzed ONCE per request (design
     * §12.1: rebuilt from CURRENT DB state — see class javadoc for why there is no true historical
     * replay in this codebase). Package-visible so {@link WhatIfService} can share it. */
    record RunContext(
            ActivityPlan plan,
            OptimizationRun run,
            AssembledProblem assembled,
            ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis,
            SolutionIndex index,
            int currentRevision,
            boolean stale) {

        GroupPlanSolution solution() {
            return assembled.solution();
        }
    }

    RunContext loadContext(String planId, String runId) {
        ActivityPlan plan = activityPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + planId));
        OptimizationRun run = optimizationRunRepository.findById(runId)
                .filter(r -> r.activityPlanId().equals(planId))
                .orElseThrow(() -> new NotFoundException("Run not found in plan " + planId + ": " + runId));
        AssembledProblem assembled = assembler.assemble(planId);
        ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis =
                solutionManager.analyze(assembled.solution(), ScoreAnalysisFetchPolicy.FETCH_ALL);
        int currentRevision = activityPlanRepository.getPlanRevision(planId);
        boolean stale = currentRevision != run.planRevision();
        return new RunContext(plan, run, assembled, baseAnalysis, SolutionIndex.of(assembled.solution()), currentRevision, stale);
    }

    PlayerAssignment requireTarget(RunContext ctx, String participantProfileId) {
        ParticipantProfile profile = participantProfileRepository.findById(participantProfileId)
                .filter(p -> p.activityPlanId().equals(ctx.plan().id()))
                .orElseThrow(() -> new NotFoundException("Participant not found in plan: " + participantProfileId));
        Long solverId = null;
        for (Map.Entry<Long, String> e : ctx.assembled().participantProfileDbIdByLongId().entrySet()) {
            if (e.getValue().equals(profile.id())) {
                solverId = e.getKey();
                break;
            }
        }
        if (solverId == null) {
            throw new NotFoundException("Participant not resolvable in the current solver state: " + participantProfileId);
        }
        Long finalSolverId = solverId;
        return ctx.solution().getPlayerAssignments().stream()
                .filter(pa -> pa.getId().equals(finalSolverId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found in solution: " + participantProfileId));
    }

    Group requireGroupFact(RunContext ctx, String groupId) {
        TrainingGroup tg = trainingGroupRepository.findById(groupId)
                .filter(g -> g.activityPlanId().equals(ctx.plan().id()))
                .orElseThrow(() -> new NotFoundException("Group not found in plan: " + groupId));
        for (Group g : ctx.solution().getGroups()) {
            if (g.groupOrder() == tg.groupOrder()) {
                return g;
            }
        }
        throw new NotFoundException("Group not resolvable in the current solver state: " + groupId);
    }

    String groupDbId(RunContext ctx, Group group) {
        return ctx.assembled().trainingGroupDbIdByLongId().get(group.id());
    }

    String participantDbId(RunContext ctx, long participantSolverId) {
        return ctx.assembled().participantProfileDbIdByLongId().get(participantSolverId);
    }

    // ─────────────────────────────────────────────────────────────────────── PERSON level

    public PersonExplanationResponse explainPerson(String planId, String runId, String participantProfileId) {
        RunContext ctx = loadContext(planId, runId);
        PlayerAssignment target = requireTarget(ctx, participantProfileId);

        ExplanationCache.Key cacheKey = new ExplanationCache.Key(runId, ctx.currentRevision(), participantProfileId);
        PersonExplanationResponse cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        PersonExplanationResponse response = target.getGroup() == null
                ? buildWaitlistedExplanation(ctx, target, participantProfileId)
                : buildPlacedExplanation(ctx, target, participantProfileId);

        cache.put(cacheKey, response);
        persistAudit(ctx, target, participantProfileId, response);
        return response;
    }

    private void persistAudit(RunContext ctx, PlayerAssignment target, String participantProfileId, PersonExplanationResponse response) {
        try {
            String selectedGroupDbId = target.getGroup() == null ? null : groupDbId(ctx, target.getGroup());
            explanationRecordRepository.upsert(
                    ctx.run().id(), participantProfileId, selectedGroupDbId,
                    writeJson(response.positiveFactors()), writeJson(response.negativeFactors()),
                    writeJson(response.alternatives()), writeJson(response.brokenWishes()),
                    writeJson(response.selectedGroup()));
        } catch (RuntimeException e) {
            // Audit persistence is best-effort (design §11.4: the LRU cache is the serving path) - a
            // write failure must never break the actual explanation response.
            log.warn("Failed to persist explanation_record audit row for run {} participant {}", ctx.run().id(), participantProfileId, e);
        }
    }

    // --- placed player -------------------------------------------------------------------

    private PersonExplanationResponse buildPlacedExplanation(RunContext ctx, PlayerAssignment target, String participantProfileId) {
        Group selectedGroup = target.getGroup();
        SolutionIndex idx = ctx.index();
        MoveProbe.GroupStats stats = MoveProbe.statsOf(ctx.solution(), selectedGroup);

        List<FactorView> positive = new ArrayList<>();
        List<FactorView> negative = new ArrayList<>();
        List<BrokenWishView> brokenWishes = new ArrayList<>();

        addLevelMatchFactor(positive, negative, target, selectedGroup, stats);
        positive.add(new FactorView("%s hade plats: %d/%d spelare".formatted(selectedGroup.name(), stats.size(), selectedGroup.maxSize())));
        addTimeFactor(positive, negative, ctx, target, selectedGroup);

        classifyParticipantMatches(ctx, target.getId(), positive, negative, brokenWishes, idx);
        addWaitlistedFriendNotices(ctx, target, brokenWishes);

        Set<String> weightKeys = new LinkedHashSet<>(ALWAYS_RELEVANT_WEIGHT_KEYS);
        if (target.getPreviousGroupOrder() == null) {
            weightKeys.remove(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY);
        } else {
            weightKeys.add(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY);
        }
        if (target.hasPreferences()) {
            weightKeys.add(ConstraintKeys.TIME_PREFERENCE_SOFT);
        }
        brokenWishes.forEach(w -> weightKeys.add(w.key()));
        List<AppliedWeightView> appliedWeights = appliedWeightsFor(ctx, weightKeys);

        List<AlternativeGroupView> alternatives = buildAlternatives(ctx, target, selectedGroup);

        SelectedGroupView selectedGroupView = new SelectedGroupView(
                groupDbId(ctx, selectedGroup), selectedGroup.name(), stats.size(), selectedGroup.targetSize(),
                selectedGroup.maxSize(), meanLabel(stats), stats.spread());

        return new PersonExplanationResponse(
                ctx.run().id(), ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(),
                participantProfileId, target.getDisplayName(), selectedGroupView, positive, negative, brokenWishes,
                appliedWeights, alternatives, null);
    }

    /** Level-vs-band factor (kravspec §17.2's "Kalles nivåscore 640 matchade Grupp Y:s nivåspann
     * 600–690"). M7 review fix M1: the "matchar" sentence is only TRUE — and therefore only emitted
     * as a positive factor — when the player's level actually lies inside {@code [levelMinScaled,
     * levelMaxScaled]}; a player placed OUTSIDE the band (which the solver may legitimately do, the
     * bands are informational per design §7 "never hard") gets an honest "ligger över/under gruppens
     * nivåspann" NEGATIVE factor instead. An explanation engine that asserts a false match is worse
     * than no engine at all (spec §17.4: förklaringar från data, aldrig gissningar). */
    private void addLevelMatchFactor(
            List<FactorView> positive, List<FactorView> negative, PlayerAssignment target, Group group, MoveProbe.GroupStats stats) {
        int bandMin = group.levelMinScaled();
        int bandMax = group.levelMaxScaled();
        int level = target.getLevelScaled();
        if (bandMax > 0) {
            String levelSv = JustificationMessages.formatLevel(level);
            String bandSv = JustificationMessages.formatLevel(bandMin) + "–" + JustificationMessages.formatLevel(bandMax);
            if (level >= bandMin && level <= bandMax) {
                positive.add(new FactorView("%s:s nivåscore %s matchar %s:s nivåspann %s".formatted(
                        target.getDisplayName(), levelSv, group.name(), bandSv)));
            } else if (level > bandMax) {
                negative.add(new FactorView("%s:s nivåscore %s ligger över %s:s nivåspann %s".formatted(
                        target.getDisplayName(), levelSv, group.name(), bandSv)));
            } else {
                negative.add(new FactorView("%s:s nivåscore %s ligger under %s:s nivåspann %s".formatted(
                        target.getDisplayName(), levelSv, group.name(), bandSv)));
            }
        } else if (stats.size() > 0) {
            // No configured band to compare against: state the two numbers neutrally instead of
            // asserting an unverifiable "ligger nära" (same truthfulness principle as the band case).
            positive.add(new FactorView("%s:s nivåscore är %s; %s:s nivåsnitt är %s".formatted(
                    target.getDisplayName(), JustificationMessages.formatLevel(level), group.name(),
                    JustificationMessages.formatLevel(stats.meanScaled()))));
        }
    }

    private void addTimeFactor(List<FactorView> positive, List<FactorView> negative, RunContext ctx, PlayerAssignment target, Group group) {
        GroupSchedule schedule = ctx.solution().getGroupSchedules().stream()
                .filter(gs -> gs.getGroup() == group)
                .findFirst()
                .orElse(null);
        if (schedule == null || schedule.getTrainingBlock() == null) {
            return;
        }
        long timeSlotId = schedule.getTrainingBlock().timeSlotId();
        if (target.canAttend(timeSlotId)) {
            positive.add(new FactorView("%s kunde träna på %s:s tid (%s)".formatted(
                    target.getDisplayName(), group.name(), ctx.index().timeSlotLabel(timeSlotId))));
        } else {
            negative.add(new FactorView("%s kan egentligen inte träna på %s:s tid (%s)".formatted(
                    target.getDisplayName(), group.name(), ctx.index().timeSlotLabel(timeSlotId))));
        }
    }

    /** Scans every {@link MatchAnalysis} in {@link RunContext#baseAnalysis()} that references this
     * participant, classifying each into positive/negative/brokenWish per its sign (docs/design/04
     * -solver.md §11.2: "fulfilled hard facts... broken wishes... derived by absence of matches plus
     * positive statements computed from data" — this method covers the "matches that DO exist and
     * reference this player" half; the "absence" half is {@link #addLevelMatchFactor}/{@link
     * #addTimeFactor} above). */
    private void classifyParticipantMatches(
            RunContext ctx, long participantId, List<FactorView> positive, List<FactorView> negative,
            List<BrokenWishView> brokenWishes, SolutionIndex idx) {
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : ctx.baseAnalysis().constraintAnalyses()) {
            for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                ConstraintJustification j = match.justification();
                if (!referencesParticipant(j, participantId)) {
                    continue;
                }
                String messageSv = JustificationMessages.toSwedish(j, idx);
                boolean isWish = WISH_KEYS.contains(ca.constraintName());
                if (isNegative(match.score())) {
                    negative.add(new FactorView(messageSv));
                    if (isWish) {
                        long weight = Math.abs(primaryComponent(ca.weight()));
                        brokenWishes.add(new BrokenWishView(
                                ca.constraintName(), otherPersonName(j, participantId, idx), weight, messageSv, null));
                    }
                } else if (isPositive(match.score())) {
                    positive.add(new FactorView(messageSv));
                }
            }
        }
    }

    /** Amendment (c) of docs/design/05-solver-verification.md: a friend-wish target who is themselves
     * unassigned produces NO Timefold match at all (both sides of a {@code sameGroupHard/Soft} join
     * require a non-null group), so this narrative is synthesized directly from the {@code
     * PersonPairWish} facts, independent of {@link #classifyParticipantMatches}. */
    private void addWaitlistedFriendNotices(RunContext ctx, PlayerAssignment target, List<BrokenWishView> brokenWishes) {
        for (PersonPairWish wish : ctx.solution().getPersonPairWishes()) {
            if (wish.aParticipantProfileId() != target.getId() && wish.bParticipantProfileId() != target.getId()) {
                continue;
            }
            long otherId = wish.aParticipantProfileId() == target.getId() ? wish.bParticipantProfileId() : wish.aParticipantProfileId();
            PlayerAssignment other = playerById(ctx, otherId);
            if (other == null || other.getGroup() != null) {
                continue;
            }
            String key = switch (wish.type()) {
                case MUST_SAME -> ConstraintKeys.SAME_GROUP_HARD;
                case WANT_SAME -> ConstraintKeys.SAME_GROUP_SOFT;
                case MUST_DIFFERENT -> ConstraintKeys.DIFFERENT_GROUP_HARD;
                case WANT_DIFFERENT -> ConstraintKeys.DIFFERENT_GROUP_SOFT;
            };
            String friendName = other.getDisplayName();
            String friendDbId = participantDbId(ctx, otherId);
            brokenWishes.add(new BrokenWishView(
                    key, friendName, 0, "%s är oplacerad (kölista)".formatted(friendName), friendDbId));
        }
    }

    private PlayerAssignment playerById(RunContext ctx, long solverId) {
        return ctx.solution().getPlayerAssignments().stream().filter(pa -> pa.getId() == solverId).findFirst().orElse(null);
    }

    /** The ONE-PASS candidate-group probe (docs/design/04-solver.md §11.3, verifier-corrected: "12
     * probes computed in one pass, rules 1-4 derived as ordering/labelling over that result set").
     * For a placed player, candidates are every OTHER group (the union rule's origin tags are then
     * applied over that already-computed result set, never re-probed). */
    private List<AlternativeGroupView> buildAlternatives(RunContext ctx, PlayerAssignment target, Group selectedGroup) {
        List<Group> sortedGroups = ctx.solution().getGroups().stream()
                .filter(g -> g != selectedGroup)
                .sorted(java.util.Comparator.comparingInt(Group::groupOrder))
                .toList();

        Map<Group, MoveProbe.Result> results = new LinkedHashMap<>();
        for (Group g : sortedGroups) {
            results.put(g, moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), target, g, ctx.index()));
        }

        Map<Group, Set<String>> origins = unionOrigins(ctx, target, selectedGroup, sortedGroups, results);

        List<AlternativeGroupView> alternatives = new ArrayList<>();
        for (Map.Entry<Group, Set<String>> e : origins.entrySet()) {
            alternatives.add(toAlternativeView(ctx, e.getKey(), results.get(e.getKey()), e.getValue()));
        }
        return alternatives;
    }

    /** Rules 1-4 of design §11.3, as pure labelling over the already-computed {@code results} map (no
     * further probing): 1) FRIEND_WISH = groups containing anyone referenced by a personRelation wish
     * of {@code target}; 2) COACH_WISH = groups whose assigned coach matches a coachRelation wish; 3)
     * PREVIOUS_GROUP = the group matching {@code target.getPreviousGroupOrder()}; 4) TOP_SCORE = the
     * top-3 REMAINING groups (not already unioned) ranked by what-if score delta, best first. */
    private Map<Group, Set<String>> unionOrigins(
            RunContext ctx, PlayerAssignment target, Group selectedGroup, List<Group> candidates, Map<Group, MoveProbe.Result> results) {
        Map<Group, Set<String>> origins = new LinkedHashMap<>();
        for (PersonPairWish wish : ctx.solution().getPersonPairWishes()) {
            if (wish.aParticipantProfileId() != target.getId() && wish.bParticipantProfileId() != target.getId()) {
                continue;
            }
            long otherId = wish.aParticipantProfileId() == target.getId() ? wish.bParticipantProfileId() : wish.aParticipantProfileId();
            PlayerAssignment other = playerById(ctx, otherId);
            if (other != null && other.getGroup() != null && other.getGroup() != selectedGroup) {
                origins.computeIfAbsent(other.getGroup(), k -> new LinkedHashSet<>()).add("FRIEND_WISH");
            }
        }
        for (CoachWish wish : ctx.solution().getCoachWishes()) {
            if (wish.participantProfileId() != target.getId()) {
                continue;
            }
            for (CoachSlot slot : ctx.solution().getCoachSlots()) {
                if (slot.getCoach() != null && slot.getCoach().personId() == wish.coachPersonId() && slot.getGroup() != selectedGroup) {
                    origins.computeIfAbsent(slot.getGroup(), k -> new LinkedHashSet<>()).add("COACH_WISH");
                }
            }
        }
        if (target.getPreviousGroupOrder() != null) {
            for (Group g : candidates) {
                if (g.groupOrder() == target.getPreviousGroupOrder()) {
                    origins.computeIfAbsent(g, k -> new LinkedHashSet<>()).add("PREVIOUS_GROUP");
                }
            }
        }
        List<Group> remaining = candidates.stream().filter(g -> !origins.containsKey(g)).toList();
        remaining.stream()
                .sorted((a, b) -> compareByScoreDeltaDesc(results.get(a), results.get(b)))
                .limit(3)
                .forEach(g -> origins.computeIfAbsent(g, k -> new LinkedHashSet<>()).add("TOP_SCORE"));
        return origins;
    }

    private static int compareByScoreDeltaDesc(MoveProbe.Result a, MoveProbe.Result b) {
        return b.scoreDelta().compareTo(a.scoreDelta());
    }

    AlternativeGroupView toAlternativeView(RunContext ctx, Group group, MoveProbe.Result result, Set<String> origins) {
        String verdict = verdictOf(result);
        String narrative = narrativeFor(ctx, group, result, verdict);
        return new AlternativeGroupView(
                groupDbId(ctx, group), group.name(), List.copyOf(origins), verdict, toScoreDeltaView(result.scoreDelta()),
                result.newlyBroken(), result.newlyFixed(), narrative);
    }

    /** M7 review fix m4: an exactly-zero diff is neither better nor worse — labelling it WORSE
     * ("skulle försämra totalpoängen") would be a false statement, the same truthfulness class as
     * review fix M1. Verdict values: {@code WOULD_BREAK_HARD | BETTER | NEUTRAL | WORSE} (NEUTRAL is
     * an M7-review extension of design §11.3's original three-value enum — documented in
     * backend/docs/m7-notes.md). */
    private static String verdictOf(MoveProbe.Result result) {
        if (result.wouldBreakHard()) {
            return "WOULD_BREAK_HARD";
        }
        if (HardMediumSoftLongScore.ZERO.equals(result.scoreDelta())) {
            return "NEUTRAL";
        }
        return result.isImprovement() ? "BETTER" : "WORSE";
    }

    /** kravspec §17.2's own example phrasing ("Grupp C var redan full: 12/12") is used verbatim
     * whenever a candidate's blocking hard violation is {@code groupMaxSizeHard} — the raw {@link
     * Justifications.GroupOverMaxJustification} message (design §14.2's own "Grupp C blir 12, max är
     * 11" phrasing) is still present verbatim in {@code newlyBroken}, this is just the friendlier
     * headline for the alternative card. */
    private String narrativeFor(RunContext ctx, Group group, MoveProbe.Result result, String verdict) {
        if ("WOULD_BREAK_HARD".equals(verdict)) {
            boolean full = result.newlyBroken().stream().anyMatch(m -> ConstraintKeys.GROUP_MAX_SIZE_HARD.equals(m.key()));
            if (full) {
                MoveProbe.GroupStats current = MoveProbe.statsOf(ctx.solution(), group);
                return "%s är full: %d/%d".formatted(group.name(), current.size(), group.maxSize());
            }
            return result.newlyBroken().isEmpty()
                    ? "%s skulle bryta ett hårt krav".formatted(group.name())
                    : result.newlyBroken().get(0).messageSv();
        }
        if ("BETTER".equals(verdict)) {
            return "%s skulle förbättra totalpoängen".formatted(group.name());
        }
        if ("NEUTRAL".equals(verdict)) {
            return "%s påverkar inte totalpoängen".formatted(group.name());
        }
        return "%s skulle försämra totalpoängen".formatted(group.name());
    }

    // --- waitlisted player (amendments a + c) ---------------------------------------------

    private PersonExplanationResponse buildWaitlistedExplanation(RunContext ctx, PlayerAssignment target, String participantProfileId) {
        List<Group> allGroups = ctx.solution().getGroups().stream()
                .sorted(java.util.Comparator.comparingInt(Group::groupOrder))
                .toList();
        Map<Group, MoveProbe.Result> results = new LinkedHashMap<>();
        for (Group g : allGroups) {
            results.put(g, moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), target, g, ctx.index()));
        }

        List<WaitlistBlockerView> blockers = new ArrayList<>();
        List<String> feasibleGroupNames = new ArrayList<>();
        for (Group g : allGroups) {
            MoveProbe.Result r = results.get(g);
            if (r.wouldBreakHard()) {
                blockers.add(new WaitlistBlockerView(groupDbId(ctx, g), g.name(), hardBlockerNarrative(ctx, g, r, target)));
            } else {
                // Verifier fix (MAJOR #1): a hard-feasible candidate for a waitlisted player is
                // mathematically ALWAYS an improvement (medium dominance) - if the solver left them
                // waitlisted anyway, that's a solver-quality issue, not a data-derived priority
                // story. Never rendered as if it were one.
                feasibleGroupNames.add(g.name());
                blockers.add(new WaitlistBlockerView(groupDbId(ctx, g), g.name(), "Plats finns — förbättring möjlig."));
            }
        }
        // M7 review fix m7: ONE aggregated warning naming every feasible group (last-wins dropped
        // all but the final one), and the solver-quality WARN logged once per (runId, participantId)
        // rather than once per feasible group per request.
        String qualityWarning = feasibleGroupNames.isEmpty()
                ? null
                : "Förbättring möjlig i %s — kör om optimeringen.".formatted(String.join(", ", feasibleGroupNames));
        if (!feasibleGroupNames.isEmpty()
                && solverQualityWarned.add(ctx.run().id() + "|" + participantProfileId)) {
            log.warn(
                    "Solver-quality warning: waitlisted participant {} has feasible non-full candidate group(s) {} "
                            + "(plan {}, run {}) after convergence - re-solving may improve the result.",
                    participantProfileId, feasibleGroupNames, ctx.plan().id(), ctx.run().id());
        }

        List<FactorView> negative = new ArrayList<>();
        List<BrokenWishView> brokenWishes = new ArrayList<>();
        classifyParticipantMatches(ctx, target.getId(), new ArrayList<>(), negative, brokenWishes, ctx.index());
        addWaitlistedFriendNotices(ctx, target, brokenWishes);

        List<AppliedWeightView> appliedWeights = appliedWeightsFor(ctx, Set.of(ConstraintKeys.UNASSIGNED_PLAYER, ConstraintKeys.GROUP_MAX_SIZE_HARD));

        String reasonSv = "%s är oplacerad (kölista), prioritet %d. Ingen grupp hade en kombination av plats, tid och krav som gick att lösa."
                .formatted(target.getDisplayName(), target.getPriority());
        WaitlistView waitlistView = new WaitlistView(reasonSv, blockers, qualityWarning);

        return new PersonExplanationResponse(
                ctx.run().id(), ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(),
                participantProfileId, target.getDisplayName(), null, List.of(), negative, brokenWishes, appliedWeights,
                List.of(), waitlistView);
    }

    /** Amendment (a): the concrete hard blocker per group, plus (for a full group specifically) the
     * priority narrative compared directly against the group's current lowest member priority - NEVER
     * derived from the probe's verdict/score, exactly the verifier's required fix. */
    private String hardBlockerNarrative(RunContext ctx, Group group, MoveProbe.Result result, PlayerAssignment target) {
        boolean full = result.newlyBroken().stream().anyMatch(m -> ConstraintKeys.GROUP_MAX_SIZE_HARD.equals(m.key()));
        if (full) {
            int size = MoveProbe.statsOf(ctx.solution(), group).size();
            int minPriority = ctx.solution().getPlayerAssignments().stream()
                    .filter(pa -> pa.getGroup() == group)
                    .mapToInt(PlayerAssignment::getPriority)
                    .min()
                    .orElse(target.getPriority());
            String priorityNote = target.getPriority() > minPriority
                    ? " Du har högre prioritet (%d) än lägsta i gruppen (%d), men gruppen är full.".formatted(target.getPriority(), minPriority)
                    : " Alla platser har prioritet ≥ din (%d).".formatted(target.getPriority());
            return "%s är full: %d/%d.%s".formatted(group.name(), size, group.maxSize(), priorityNote);
        }
        boolean timeBlocked = result.newlyBroken().stream().anyMatch(m -> ConstraintKeys.TIME_AVAILABILITY_HARD.equals(m.key()));
        if (timeBlocked) {
            return "%s kan inte tiden för %s".formatted(target.getDisplayName(), group.name());
        }
        boolean wishBlocked = result.newlyBroken().stream()
                .anyMatch(m -> ConstraintKeys.SAME_GROUP_HARD.equals(m.key()) || ConstraintKeys.DIFFERENT_GROUP_HARD.equals(m.key()));
        if (wishBlocked) {
            return "%s bryter ett måste-krav om spelpartner för %s".formatted(group.name(), target.getDisplayName());
        }
        boolean coachWishBlocked = result.newlyBroken().stream()
                .anyMatch(m -> ConstraintKeys.COACH_WISH_REQUIRED.equals(m.key()) || ConstraintKeys.COACH_WISH_FORBIDDEN.equals(m.key()));
        if (coachWishBlocked) {
            return "%s bryter ett tränarkrav för %s".formatted(group.name(), target.getDisplayName());
        }
        return result.newlyBroken().isEmpty()
                ? "%s går inte att placera i just nu".formatted(group.name())
                : result.newlyBroken().get(0).messageSv();
    }

    // ─────────────────────────────────────────────────────────────────────── GROUP level

    public GroupExplanationResponse explainGroup(String planId, String runId, String groupId) {
        RunContext ctx = loadContext(planId, runId);
        Group group = requireGroupFact(ctx, groupId);
        MoveProbe.GroupStats stats = MoveProbe.statsOf(ctx.solution(), group);

        GroupSchedule schedule = ctx.solution().getGroupSchedules().stream().filter(gs -> gs.getGroup() == group).findFirst().orElse(null);
        GroupBlockView blockView = null;
        if (schedule != null && schedule.getTrainingBlock() != null) {
            blockView = new GroupBlockView(
                    ctx.assembled().trainingBlockDbIdByLongId().get(schedule.getTrainingBlock().id()), schedule.getTrainingBlock().label());
        }

        CoachSlot coachSlot = ctx.solution().getCoachSlots().stream()
                .filter(cs -> cs.getGroup() == group && cs.getCoach() != null)
                .findFirst()
                .orElse(null);
        GroupCoachView coachView = null;
        if (coachSlot != null) {
            CoachFact coach = coachSlot.getCoach();
            String coachDbId = ctx.assembled().coachProfileDbIdByLongId().get(coach.coachProfileId());
            coachView = new GroupCoachView(coachDbId, coach.displayName());
        }

        List<String> warnings = new ArrayList<>();
        List<GroupMatchView> matches = new ArrayList<>();
        List<GroupMemberBrokenWishView> membersWithBrokenWishes = new ArrayList<>();
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : ctx.baseAnalysis().constraintAnalyses()) {
            for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                ConstraintJustification j = match.justification();
                if (!referencesGroup(j, group.id(), ctx.index())) {
                    continue;
                }
                String messageSv = JustificationMessages.toSwedish(j, ctx.index());
                matches.add(new GroupMatchView(ca.constraintName(), messageSv, toScoreDeltaView(match.score())));
                if (isNegative(match.score())) {
                    ConstraintMetadata.Meta meta = ConstraintMetadata.of(ca.constraintName());
                    warnings.add(levelLabelSv(meta.level()) + ": " + messageSv);
                }
                if (WISH_KEYS.contains(ca.constraintName()) && isNegative(match.score())) {
                    for (long memberRef : participantRefsInGroup(j, group.id(), ctx.index())) {
                        String pid = participantDbId(ctx, memberRef);
                        if (pid != null) {
                            membersWithBrokenWishes.add(new GroupMemberBrokenWishView(pid, ctx.index().participantName(memberRef), messageSv));
                        }
                    }
                }
            }
        }
        if (stats.size() > group.maxSize()) {
            warnings.add("Gruppen är över maxstorlek: %d/%d".formatted(stats.size(), group.maxSize()));
        }
        if (coachSlot == null && group.requiredCoachCount() > 0) {
            warnings.add("Gruppen saknar tränare");
        }

        return new GroupExplanationResponse(
                ctx.run().id(), ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(),
                groupId, group.name(), stats.size(), group.targetSize(), group.maxSize(), meanLabel(stats), stats.spread(),
                coachView, blockView, warnings, matches, membersWithBrokenWishes);
    }

    // ─────────────────────────────────────────────────────────────────────── PLAN level

    public PlanExplanationResponse explainPlan(String planId, String runId) {
        RunContext ctx = loadContext(planId, runId);
        HardMediumSoftLongScore score = ctx.baseAnalysis().score();

        List<ConstraintSummaryView> constraintSummaries = new ArrayList<>();
        List<HardViolationView> hardViolations = new ArrayList<>();
        Map<Long, Long> penaltySumByGroupId = new LinkedHashMap<>();
        List<WaitlistEntryView> waitlist = new ArrayList<>();
        List<ManualReviewEntryView> manualReview = new ArrayList<>();

        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : ctx.baseAnalysis().constraintAnalyses()) {
            ConstraintMetadata.Meta meta = ConstraintMetadata.of(ca.constraintName());
            constraintSummaries.add(new ConstraintSummaryView(
                    ca.constraintName(), meta.label(), meta.level(), Math.abs(primaryComponent(ca.weight())), primaryComponent(ca.score()),
                    ca.matchCount()));
            for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                ConstraintJustification j = match.justification();
                if (isNegative(match.score()) && "HARD".equals(meta.level())) {
                    hardViolations.add(new HardViolationView(ca.constraintName(), JustificationMessages.toSwedish(j, ctx.index())));
                }
                for (Long groupId : groupRefsOf(j, ctx.index())) {
                    penaltySumByGroupId.merge(groupId, Math.abs(primaryComponent(match.score())), Long::sum);
                }
                if (j instanceof Justifications.UnassignedPlayerJustification u) {
                    String pid = participantDbId(ctx, u.participantId());
                    waitlist.add(new WaitlistEntryView(pid, u.displayName(), u.priority(), waitlistReasonFor(ctx, u.participantId())));
                }
            }
        }
        constraintSummaries.sort(java.util.Comparator.comparing(ConstraintSummaryView::key));

        List<ProblematicGroupView> problematicGroups = penaltySumByGroupId.entrySet().stream()
                .map(e -> new ProblematicGroupView(ctx.assembled().trainingGroupDbIdByLongId().get(e.getKey()), ctx.index().groupName(e.getKey()), e.getValue()))
                .filter(v -> v.groupId() != null)
                .sorted((a, b) -> Long.compare(b.penaltySum(), a.penaltySum()))
                .toList();

        for (ParticipantProfile p : participantProfileRepository.findByActivityPlanId(planId)) {
            if (p.manualReviewFlag()) {
                manualReview.add(new ManualReviewEntryView(p.id(), displayNameOf(p), "Flaggad för manuell granskning"));
            }
        }

        return new PlanExplanationResponse(
                ctx.run().id(), ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(),
                toScoreDeltaView(score), score.isFeasible(), constraintSummaries, hardViolations, waitlist, problematicGroups, manualReview);
    }

    private String waitlistReasonFor(RunContext ctx, long participantId) {
        PlayerAssignment p = playerById(ctx, participantId);
        if (p == null) {
            return "Oplacerad";
        }
        long fullCount = ctx.solution().getGroups().stream()
                .filter(g -> MoveProbe.statsOf(ctx.solution(), g).size() >= g.maxSize())
                .count();
        if (fullCount == ctx.solution().getGroups().size() && !ctx.solution().getGroups().isEmpty()) {
            return "Alla grupper var fulla med spelare av minst lika hög prioritet.";
        }
        return "Ingen grupp hade en kombination av plats, tid och krav som gick att lösa (prioritet %d).".formatted(p.getPriority());
    }

    private String displayNameOf(ParticipantProfile p) {
        return personRepository.findById(p.personId()).map(this::displayName).orElse(p.id());
    }

    private String displayName(Person person) {
        if (person.displayName() != null && !person.displayName().isBlank()) {
            return person.displayName();
        }
        return (person.firstName() + " " + person.lastName()).strip();
    }

    // ─────────────────────────────────────────────────────────────────────── shared helpers

    List<AppliedWeightView> appliedWeightsFor(RunContext ctx, Set<String> keys) {
        List<AppliedWeightView> views = new ArrayList<>();
        for (String key : keys) {
            ConstraintMetadata.Meta meta = ConstraintMetadata.of(key);
            HardMediumSoftLongScore weight = ctx.solution().getConstraintWeightOverrides().getKnownConstraintNames().contains(key)
                    ? ctx.solution().getConstraintWeightOverrides().getConstraintWeight(key)
                    : HardMediumSoftLongScore.ZERO;
            views.add(new AppliedWeightView(key, meta.label(), meta.level(), Math.abs(primaryComponent(weight))));
        }
        views.sort(java.util.Comparator.comparing(AppliedWeightView::key));
        return views;
    }

    private static String meanLabel(MoveProbe.GroupStats stats) {
        return stats.size() == 0 ? null : JustificationMessages.formatLevel(stats.meanScaled());
    }

    /** M7 review fix m3: never leak the raw enum-ish HARD/MEDIUM/SOFT strings into a Swedish
     * display sentence — {@code warnings} entries are user-facing text (unlike {@code
     * ConstraintSummaryView.level}, which is a machine-readable FIELD the frontend maps itself). */
    private static String levelLabelSv(String level) {
        return switch (level) {
            case HardOrSoft.HARD -> "Hård regel";
            case HardOrSoft.MEDIUM -> "Systemregel";
            case HardOrSoft.SOFT -> "Mjuk regel";
            default -> level;
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize explanation_record JSON", e);
        }
    }

    static ScoreDeltaView toScoreDeltaView(HardMediumSoftLongScore score) {
        return new ScoreDeltaView(score.hardScore(), score.mediumScore(), score.softScore());
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

    private static boolean isNegative(HardMediumSoftLongScore s) {
        if (s.hardScore() != 0) {
            return s.hardScore() < 0;
        }
        if (s.mediumScore() != 0) {
            return s.mediumScore() < 0;
        }
        return s.softScore() < 0;
    }

    private static boolean isPositive(HardMediumSoftLongScore s) {
        if (s.hardScore() != 0) {
            return s.hardScore() > 0;
        }
        if (s.mediumScore() != 0) {
            return s.mediumScore() > 0;
        }
        return s.softScore() > 0;
    }

    private static boolean referencesParticipant(ConstraintJustification j, long participantId) {
        return switch (j) {
            case Justifications.TimeUnavailableJustification x -> x.participantId() == participantId;
            case Justifications.PairWishBrokenJustification x -> x.aParticipantId() == participantId || x.bParticipantId() == participantId;
            case Justifications.PairWishSoftJustification x -> x.aParticipantId() == participantId || x.bParticipantId() == participantId;
            case Justifications.ContinuityJustification x -> x.participantId() == participantId;
            case Justifications.TimePreferenceMissedJustification x -> x.participantId() == participantId;
            case Justifications.UnassignedPlayerJustification x -> x.participantId() == participantId;
            case Justifications.CoachWishJustification x -> x.participantId() == participantId;
            default -> false;
        };
    }

    private static Long participantRefOf(ConstraintJustification j) {
        return switch (j) {
            case Justifications.TimeUnavailableJustification x -> x.participantId();
            case Justifications.ContinuityJustification x -> x.participantId();
            case Justifications.TimePreferenceMissedJustification x -> x.participantId();
            case Justifications.UnassignedPlayerJustification x -> x.participantId();
            case Justifications.CoachWishJustification x -> x.participantId();
            case Justifications.PairWishBrokenJustification x -> x.aParticipantId();
            case Justifications.PairWishSoftJustification x -> x.aParticipantId();
            default -> null;
        };
    }

    private static String otherPersonName(ConstraintJustification j, long participantId, SolutionIndex idx) {
        return switch (j) {
            case Justifications.PairWishBrokenJustification x -> idx.participantName(
                    x.aParticipantId() == participantId ? x.bParticipantId() : x.aParticipantId());
            case Justifications.PairWishSoftJustification x -> idx.participantName(
                    x.aParticipantId() == participantId ? x.bParticipantId() : x.aParticipantId());
            case Justifications.CoachWishJustification x -> idx.personName(x.coachPersonId());
            default -> null;
        };
    }

    private static boolean referencesGroup(ConstraintJustification j, long groupId, SolutionIndex idx) {
        return groupRefsOf(j, idx).contains(groupId);
    }

    /** {@code idx} is needed because pair/coach-wish justifications carry PARTICIPANT ids, not group
     * ids directly (§10.11-10.14/§10.21b/c's join chain never puts a group in the justification
     * record) — resolving "which group(s) does this match touch" for those types means looking up
     * each referenced participant's CURRENT group via the same {@link SolutionIndex} everything else
     * in this class already uses. */
    private static List<Long> groupRefsOf(ConstraintJustification j, SolutionIndex idx) {
        return switch (j) {
            case Justifications.BlockDoubleBookedJustification x -> List.of(x.groupIdA(), x.groupIdB());
            case Justifications.GroupOverMaxJustification x -> List.of(x.groupId());
            case Justifications.TimeUnavailableJustification x -> List.of(x.groupId());
            case Justifications.CoachDoubleBookedJustification x -> List.of(x.groupIdA(), x.groupIdB());
            case Justifications.PlayerDoubleBookedJustification x -> List.of(x.groupIdA(), x.groupIdB());
            case Justifications.TrainAndCoachClashJustification x -> List.of(x.coachGroupId(), x.playGroupId());
            case Justifications.CoachUnavailableJustification x -> List.of(x.groupId());
            case Justifications.MissingCoachJustification x -> List.of(x.groupId());
            case Justifications.SavedPlanClashJustification x -> List.of(x.groupId());
            case Justifications.GroupSizeDeviationJustification x -> List.of(x.groupId());
            case Justifications.GroupUnderMinJustification x -> List.of(x.groupId());
            case Justifications.LevelSpreadJustification x -> List.of(x.groupId());
            case Justifications.GroupOrderInversionJustification x -> List.of(x.higherGroupId(), x.lowerGroupId());
            case Justifications.TimePreferenceMissedJustification x -> List.of(x.groupId());
            case Justifications.CoachLevelMismatchJustification x -> List.of(x.groupId());
            case Justifications.LateTimeJustification x -> List.of(x.groupId());
            case Justifications.CoachPreferredTimeSlotJustification x -> List.of(x.groupId());
            case Justifications.PairWishBrokenJustification x -> groupsOfParticipants(idx, x.aParticipantId(), x.bParticipantId());
            case Justifications.PairWishSoftJustification x -> groupsOfParticipants(idx, x.aParticipantId(), x.bParticipantId());
            case Justifications.CoachWishJustification x -> groupsOfParticipants(idx, x.participantId());
            default -> List.of();
        };
    }

    /** The subset of a WISH justification's referenced participant(s) who are ACTUALLY a member of
     * {@code groupId} — used for {@code GroupExplanationResponse.membersWithBrokenWishes}, where
     * (unlike {@link #participantRefOf}, which always picks the wish's "a" side for the person-level
     * view) we need whichever side of a pair wish belongs to THIS specific group, since a broken
     * pair wish by definition has its two participants in different groups. */
    private static List<Long> participantRefsInGroup(ConstraintJustification j, long groupId, SolutionIndex idx) {
        List<Long> refs = switch (j) {
            case Justifications.PairWishBrokenJustification x -> List.of(x.aParticipantId(), x.bParticipantId());
            case Justifications.PairWishSoftJustification x -> List.of(x.aParticipantId(), x.bParticipantId());
            case Justifications.CoachWishJustification x -> List.of(x.participantId());
            default -> List.<Long>of();
        };
        List<Long> inGroup = new ArrayList<>();
        for (long ref : refs) {
            PlayerAssignment pa = idx.player(ref);
            if (pa != null && pa.getGroup() != null && pa.getGroup().id() == groupId) {
                inGroup.add(ref);
            }
        }
        return inGroup;
    }

    private static List<Long> groupsOfParticipants(SolutionIndex idx, long... participantIds) {
        List<Long> groups = new ArrayList<>();
        for (long id : participantIds) {
            PlayerAssignment pa = idx.player(id);
            if (pa != null && pa.getGroup() != null) {
                groups.add(pa.getGroup().id());
            }
        }
        return groups;
    }
}
