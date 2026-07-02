package se.klubb.groupplanner.solver.assemble;

/**
 * §15.5 "Optimera endast X" (spec §15.5, docs/design/04-solver.md §5 note): which planning-entity
 * CLASSES a solve is allowed to touch. {@code false} for a class means "pin every entity of that
 * class at its current DB value", realized by forcing {@code pinned = true} on every entity of that
 * class regardless of its individual {@code locked} column (see {@code SolverInputAssembler
 * #applyClassPinning}). The default (every field {@code true}) reproduces M6a's behavior exactly:
 * only individually-locked rows are pinned.
 *
 * <p>Per the design's own note ("requires a previously-solved plan"), pinning a whole class 400s if
 * any entity in that class still carries a null value for the variable being pinned — a
 * class-level pin is a coarse "leave this whole category exactly as it is" switch, not a partial
 * lock, so every entity in the class must already carry a real value to pin.
 */
public record OptimizeSelection(boolean players, boolean schedule, boolean coaches) {

    public static final OptimizeSelection ALL = new OptimizeSelection(true, true, true);
}
