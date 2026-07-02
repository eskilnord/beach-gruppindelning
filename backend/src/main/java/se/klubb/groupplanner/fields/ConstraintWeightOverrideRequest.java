package se.klubb.groupplanner.fields;

/**
 * One entry of {@code PUT /api/plans/{planId}/constraint-weights}: a partial override for a single
 * {@code constraint_definition} row. Any of {@code hardOrSoft}/{@code weight}/{@code enabled} may be
 * omitted (null) to keep that field at its currently-effective value (override if one exists, else
 * the constraint_definition default) — see {@link ConstraintWeightService#applyOverrides}.
 */
public record ConstraintWeightOverrideRequest(String key, String hardOrSoft, Integer weight, Boolean enabled) {
}
