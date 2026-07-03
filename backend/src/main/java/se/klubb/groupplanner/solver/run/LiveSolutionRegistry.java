package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * "Watch it optimize live" projection (v0.3.0 WI-2, user feedback: "det är en nice
 * marknadsföringsgrej att man kan se hur det genereras olika scenarion väldigt snabbt" — while a
 * solve runs, the frontend polls {@code GET /api/plans/{planId}/solve/live} to show groups being
 * formed and reshuffled in near-real-time). One in-memory snapshot per plan, overwritten on every
 * best-solution event fired by {@code SolverManager} — {@code
 * se.klubb.groupplanner.solver.run.SolveCoordinator#startSolve}'s {@code
 * withBestSolutionEventConsumer} runs on THE SOLVER'S OWN THREAD, so {@link #onBestSolution}
 * projection here must stay allocation-light and side-effect-free (no DB, no logging beyond what's
 * already unavoidable) — every millisecond spent here delays the next solving step. {@code
 * event.solution()} is Timefold's own defensive clone of the best solution found so far, so
 * iterating it here needs no extra synchronization on the entity graph itself.
 *
 * <p>Deliberately NOT persisted anywhere — no {@code *_json} column, no DB table, no leak surface
 * for the confidentiality rules (names shown here already appear in the Resultatvy for the same
 * plan; nothing here is more sensitive than what {@code GroupPlanSolution.PlayerAssignment} already
 * carries). Lost on backend restart, which is fine — a restarted backend has no solve running.
 *
 * <p>Lifecycle: {@link #start} seeds sequence 0 from the pre-solve assembled problem (so the view
 * isn't empty for the first few seconds before the construction heuristic's first best-solution
 * event fires); every {@link #onBestSolution} call bumps both counters. The registry does NOT clear
 * on finish/cancel/exception — the last frame stays visible (the frontend stops polling once {@code
 * GET .../solve/status} settles to a non-solving status, so an intentionally-kept stale frame never
 * flashes to empty); the plan's entry is simply overwritten wholesale by the START of the NEXT solve,
 * so memory stays bounded at one entry per plan regardless of how many solves run over the app's
 * lifetime.
 */
@Component
public class LiveSolutionRegistry {

    /** One player chip. {@code levelScaled} mirrors {@code solver.domain.PlayerAssignment}'s own
     *  fixed-point ×100 representation (ADR-006) — this is a display-only snapshot field, not solver
     *  input, so it is exempt from the {@code solver.domain}/{@code solver.constraints} float ban. */
    public record LivePlayer(String participantProfileId, String displayName, int levelScaled) {
    }

    public record LiveGroup(String groupId, String name, List<LivePlayer> players) {
    }

    public record LiveSnapshot(
            String runId,
            /** Monotonic per-plan frame counter (0 = pre-solve seed, then +1 per best-solution
             *  event) — lets the client cheaply skip an unchanged frame by comparing sequence
             *  numbers instead of deep-diffing the payload. */
            int sequence,
            long hard,
            long medium,
            long soft,
            boolean feasible,
            /** Mirrors {@link ProgressRegistry.Progress#improvementCount()} exactly (0 at the
             *  pre-solve seed, +1 per best-solution event) so the two panels never disagree on "how
             *  many improvements so far". */
            int improvementCount,
            List<LiveGroup> groups,
            List<LivePlayer> waitlist,
            long capturedAtMillis) {
    }

    private final Map<String, LiveSnapshot> byActivityPlanId = new ConcurrentHashMap<>();

    /** Seeds sequence 0 from the assembled (pre-solve) problem — called synchronously right before
     *  {@code solverManager.solveBuilder()...run()}, so the live view has something to show
     *  immediately rather than staying empty until the construction heuristic's first callback. */
    public void start(String activityPlanId, String runId, AssembledProblem assembled) {
        byActivityPlanId.put(activityPlanId, project(runId, 0, 0, assembled.solution(), assembled));
    }

    /** Called from the solver thread's best-solution consumer (see class javadoc) — keep this fast. */
    public void onBestSolution(String activityPlanId, String runId, GroupPlanSolution solution, AssembledProblem assembled) {
        byActivityPlanId.compute(activityPlanId, (id, previous) -> {
            if (previous != null && !previous.runId().equals(runId)) {
                // Stale event from an OLDER run - never clobber a newer run's frame. Reachable
                // (verified against Timefold 1.33.0's ConsumerSupport): consumeFinalBestSolution()
                // schedules the final-best consumer asynchronously WITHOUT joining it, and
                // solvingTerminated() flips SolverStatus to NOT_SOLVING before close() joins - so
                // run A's final re-projection (SolveCoordinator#onFinalBestSolution) can land AFTER
                // run B has already passed the NOT_SOLVING start gate and seeded sequence 0 via
                // start(). Without this guard, B's fresh seed would be overwritten by A's groups
                // stamped with A's runId.
                return previous;
            }
            int nextSequence = (previous != null ? previous.sequence() : 0) + 1;
            int nextImprovementCount = (previous != null ? previous.improvementCount() : 0) + 1;
            return project(runId, nextSequence, nextImprovementCount, solution, assembled);
        });
    }

    public Optional<LiveSnapshot> get(String activityPlanId) {
        return Optional.ofNullable(byActivityPlanId.get(activityPlanId));
    }

    private static LiveSnapshot project(
            String runId, int sequence, int improvementCount, GroupPlanSolution solution, AssembledProblem assembled) {
        HardMediumSoftLongScore score = solution.getScore();
        long hard = score != null ? score.hardScore() : 0L;
        long medium = score != null ? score.mediumScore() : 0L;
        long soft = score != null ? score.softScore() : 0L;
        boolean feasible = score != null && score.isFeasible();

        Map<Long, List<LivePlayer>> playersByGroupId = new HashMap<>();
        List<LivePlayer> waitlist = new ArrayList<>();
        for (PlayerAssignment pa : solution.getPlayerAssignments()) {
            String participantDbId = assembled.participantProfileDbIdByLongId().get(pa.getId());
            if (participantDbId == null) {
                continue; // defensive: should not happen, mirrors SolveCoordinator#persistResult's own null-check.
            }
            LivePlayer player = new LivePlayer(participantDbId, pa.getDisplayName(), pa.getLevelScaled());
            Group group = pa.getGroup();
            // Defensive consistency with the group filter below: a group whose DB-id lookup fails
            // is dropped from the grid (unreachable per SolverInputAssembler's guarantees - every
            // Group fact comes from a training_group row), so its players must NOT silently vanish
            // with it; route them to the waitlist bucket instead, same philosophy as the
            // participant null-check above (skip the unmappable identity, never lose a person).
            if (group == null || assembled.trainingGroupDbIdByLongId().get(group.id()) == null) {
                waitlist.add(player);
            } else {
                playersByGroupId.computeIfAbsent(group.id(), key -> new ArrayList<>()).add(player);
            }
        }

        List<LiveGroup> groups = solution.getGroups().stream()
                .sorted(Comparator.comparingInt(Group::groupOrder))
                .map(g -> {
                    String groupDbId = assembled.trainingGroupDbIdByLongId().get(g.id());
                    return groupDbId == null ? null : new LiveGroup(groupDbId, g.name(), playersByGroupId.getOrDefault(g.id(), List.of()));
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new LiveSnapshot(
                runId, sequence, hard, medium, soft, feasible, improvementCount, groups, waitlist, System.currentTimeMillis());
    }
}
