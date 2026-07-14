package se.klubb.groupplanner.solver.assemble;

import java.util.Map;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * A {@link GroupPlanSolution} plus the reverse ({@code long solver id -> DB TEXT id}) lookups
 * {@code se.klubb.groupplanner.solver.run.SolveCoordinator} needs to write the solved result back
 * to {@code player_assignment}/{@code training_group}/{@code coach_assignment} after an async solve
 * completes (a different request thread than the one that called {@code
 * SolverInputAssembler.assemble}, so these maps cannot simply be local variables — see {@link
 * SolverIdIndex}'s javadoc for why the solver's ids are not the DB's own ids in the first place).
 */
public record AssembledProblem(
        GroupPlanSolution solution,
        Map<Long, String> participantProfileDbIdByLongId,
        Map<Long, String> coachProfileDbIdByLongId,
        Map<Long, String> trainingGroupDbIdByLongId,
        Map<Long, String> trainingBlockDbIdByLongId,
        /** {@code plan_revision} read as the FIRST database access of {@code
         *  SolverInputAssembler.assemble} — the baseline {@code
         *  se.klubb.groupplanner.solver.run.SolveResultWriteback}'s revision CAS compares against at
         *  writeback time, so any mutation that commits between assembly and writeback (a manual
         *  move, a lock/unlock, ...) is detected instead of silently overwritten. */
        int planRevisionAtAssemble) {
}
