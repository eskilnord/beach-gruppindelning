package se.klubb.groupplanner.solver.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.constraints.LevelMath;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/**
 * Deterministic no-Timefold baseline (spec §16.7, docs/design/04-solver.md §9.5, amended by
 * docs/design/05-solver-verification.md's minor finding on greedy tie-breaks): sorts players by
 * level, slices them evenly across the generated groups, assigns blocks to groups by group order
 * against blocks sorted {@code (day/epochDay, startMinute, courtId, blockId)}, and assigns coaches
 * greedily by best {@code coachLevelFit} distance then availability, coach ties broken by {@code
 * (distance, coachProfileId)} — every tie-break named exactly as the verifier's fix requires, so
 * greedy is bit-identically reproducible cross-OS (verified by {@code SolverRegressionTest}
 * asserting exact greedy-score equality alongside the solver's own golden scores).
 *
 * <p><b>Deliberately ignores {@code pinned}</b>: this is plain Java, not Timefold, so there is no
 * {@code @PlanningPin} mechanism to respect here. Locked rows are still safe because {@code
 * SolveCoordinator#persistResult} (the SAME writeback method the real solver uses) scopes every
 * repository UPDATE/DELETE to {@code locked = 0} — a locked entity's in-memory value may be
 * overwritten by this service, but the DB write for it is always a no-op. This is a deliberate
 * simplification (not a design deviation in the load-bearing sense): implementing lock-awareness
 * here would duplicate Timefold's own pinning semantics in plain Java for a component whose entire
 * purpose (spec §16.7: "fallback, jämförelse, debugging") is to be the SIMPLEST possible baseline.
 *
 * <p>Also ignores capacity (a group may end up over {@code maxSize}) and coach/player time-overlap
 * beyond a coach's own already-used slots — "fördela ungefär jämnt... tilldela... enklast möjligt"
 * (spec's own wording) is a best-effort baseline, not a feasible solver; its score (often hard
 * &lt; 0) is exactly what makes it a useful lower-bound comparison point for the real solve.
 */
@Service
public class GreedyBaselineService {

    /** Mutates and returns {@code problem} in place (matching {@code SolutionManager.update}'s own
     * in-place convention) — callers must call {@code solutionManager.update(problem)} afterward to
     * compute the resulting score (this service never touches Timefold APIs itself). */
    public GroupPlanSolution run(GroupPlanSolution problem) {
        assignGroups(problem);
        assignBlocks(problem);
        assignCoaches(problem);
        return problem;
    }

    private void assignGroups(GroupPlanSolution problem) {
        List<Group> groupsByOrder = problem.getGroups().stream()
                .sorted(Comparator.comparingInt(Group::groupOrder))
                .toList();
        List<PlayerAssignment> players = problem.getPlayerAssignments().stream()
                .sorted(Comparator.comparingInt(PlayerAssignment::getLevelScaled).reversed()
                        .thenComparingLong(PlayerAssignment::getId))
                .toList();
        if (groupsByOrder.isEmpty()) {
            players.forEach(p -> p.setGroup(null));
            return;
        }
        int n = players.size();
        int groupCount = groupsByOrder.size();
        int base = n / groupCount;
        int remainder = n % groupCount;
        int index = 0;
        for (int g = 0; g < groupCount; g++) {
            int sliceSize = base + (g < remainder ? 1 : 0);
            Group group = groupsByOrder.get(g);
            for (int i = 0; i < sliceSize; i++) {
                players.get(index++).setGroup(group);
            }
        }
    }

    /** Blocks sorted {@code (day/epochDay, startMinute, courtId, blockId)} — the verifier's tie-break
     * fix for the design's original {@code (startMinute, courtId)} (which omitted day entirely).
     * Schedules assigned by {@code groupOrder} ascending. */
    private void assignBlocks(GroupPlanSolution problem) {
        List<TrainingBlock> sortedBlocks = problem.getTrainingBlocks().stream()
                .sorted(Comparator
                        .comparingInt((TrainingBlock b) -> b.timeKey().epochDay())
                        .thenComparingInt(b -> b.timeKey().dayOfWeek())
                        .thenComparingInt(b -> b.timeKey().startMinuteOfDay())
                        .thenComparingLong(TrainingBlock::courtId)
                        .thenComparingLong(TrainingBlock::id))
                .toList();
        List<GroupSchedule> sortedSchedules = problem.getGroupSchedules().stream()
                .sorted(Comparator.comparingInt(gs -> gs.getGroup().groupOrder()))
                .toList();
        for (int i = 0; i < sortedSchedules.size() && i < sortedBlocks.size(); i++) {
            sortedSchedules.get(i).setTrainingBlock(sortedBlocks.get(i));
        }
    }

    /** Greedy coach assignment by {@code coachLevelFit}-style distance (best fit first), tie-broken
     * by {@code (distance, coachProfileId)} per the verifier's fix; respects {@code maxGroups} and
     * avoids double-booking a coach at an overlapping time (using the same {@code TimeKey.overlaps}
     * the real constraints use), skipping a slot (leaving it {@code null}, matching {@code
     * coachRequirementHard}'s shortfall shape) when no coach is available. */
    private void assignCoaches(GroupPlanSolution problem) {
        Map<Group, GroupSchedule> scheduleByGroup = new HashMap<>();
        problem.getGroupSchedules().forEach(gs -> scheduleByGroup.put(gs.getGroup(), gs));
        Map<Group, Long> groupMeanScaled = computeGroupMeans(problem);

        Map<Long, Integer> assignedCountByCoach = new HashMap<>();
        Map<Long, List<TimeKey>> usedTimesByCoach = new HashMap<>();

        List<CoachSlot> slots = problem.getCoachSlots().stream()
                .sorted(Comparator.comparingInt((CoachSlot cs) -> cs.getGroup().groupOrder())
                        .thenComparingInt(CoachSlot::getSlotIndex))
                .toList();

        for (CoachSlot slot : slots) {
            GroupSchedule schedule = scheduleByGroup.get(slot.getGroup());
            TrainingBlock block = schedule == null ? null : schedule.getTrainingBlock();
            long groupMean = groupMeanScaled.getOrDefault(slot.getGroup(), midBandScaled(slot.getGroup()));

            CoachFact best = null;
            long bestDistance = Long.MAX_VALUE;
            for (CoachFact coach : problem.getCoaches()) {
                if (assignedCountByCoach.getOrDefault(coach.coachProfileId(), 0) >= coach.maxGroups()) {
                    continue;
                }
                if (block != null && !coach.availableAt(block.timeSlotId())) {
                    continue;
                }
                if (block != null && overlapsAny(usedTimesByCoach.getOrDefault(coach.coachProfileId(), List.of()), block.timeKey())) {
                    continue;
                }
                long distance = distanceFromBand(coach, groupMean);
                if (best == null
                        || distance < bestDistance
                        || (distance == bestDistance && coach.coachProfileId() < best.coachProfileId())) {
                    best = coach;
                    bestDistance = distance;
                }
            }

            slot.setCoach(best);
            if (best != null) {
                assignedCountByCoach.merge(best.coachProfileId(), 1, Integer::sum);
                if (block != null) {
                    usedTimesByCoach.computeIfAbsent(best.coachProfileId(), k -> new ArrayList<>()).add(block.timeKey());
                }
            }
        }
    }

    private static boolean overlapsAny(List<TimeKey> used, TimeKey candidate) {
        for (TimeKey key : used) {
            if (key.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Group, Long> computeGroupMeans(GroupPlanSolution problem) {
        Map<Group, long[]> sumAndCount = new HashMap<>();
        for (PlayerAssignment pa : problem.getPlayerAssignments()) {
            Group group = pa.getGroup();
            if (group == null) {
                continue;
            }
            long[] acc = sumAndCount.computeIfAbsent(group, g -> new long[2]);
            acc[0] += pa.getLevelScaled();
            acc[1] += 1;
        }
        Map<Group, Long> means = new HashMap<>();
        sumAndCount.forEach((group, acc) -> means.put(group, LevelMath.floorMean(acc[0], (int) acc[1])));
        return means;
    }

    /** Fallback mean for an empty group: the seeded level band's midpoint (floorDiv, no float). */
    private static long midBandScaled(Group group) {
        return LevelMath.floorMean((long) group.levelMinScaled() + group.levelMaxScaled(), 2);
    }

    private static long distanceFromBand(CoachFact coach, long groupMeanScaled) {
        if (groupMeanScaled < coach.canCoachMinScaled()) {
            return coach.canCoachMinScaled() - groupMeanScaled;
        }
        if (groupMeanScaled > coach.canCoachMaxScaled()) {
            return groupMeanScaled - coach.canCoachMaxScaled();
        }
        return 0L;
    }
}
