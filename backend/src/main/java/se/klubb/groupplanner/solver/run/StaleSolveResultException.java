package se.klubb.groupplanner.solver.run;

/**
 * Thrown by {@link SolveResultWriteback#persist} when the plan's {@code plan_revision} moved
 * between {@code SolverInputAssembler.assemble} and the writeback (another mutation — a manual
 * move, a lock/unlock, a second solve's own writeback — committed while this solve was running).
 * Caught as a plain {@code RuntimeException} by both {@code SolveCoordinator} call sites and routed
 * to {@code OptimizationRunService#failRun}, leaving the DB exactly as it was — never a silent
 * overwrite of the concurrent change.
 */
public class StaleSolveResultException extends RuntimeException {

    public StaleSolveResultException(int revisionAtAssemble, int currentRevision) {
        super("Planen ändrades medan optimeringen pågick – resultatet sparades inte. Kör optimeringen igen."
                + " (revision " + revisionAtAssemble + " -> " + currentRevision + ")");
    }
}
