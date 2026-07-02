package se.klubb.groupplanner.fields;

import java.util.Set;

/**
 * The {@code field_definition.hard_or_soft} / {@code constraint_weight_config.hard_or_soft} value
 * space (spec §9.5: "Hard/Medium/Soft/Information"). The datamodel allows all four; the MVP API
 * restricts what a user can actually set:
 *
 * <ul>
 *   <li>{@link #HARD}/{@link #SOFT} — settable on any field/constraint that
 *       {@code affectsOptimization}.
 *   <li>{@link #INFO} — the classification for fields that do NOT affect optimization.
 *   <li>{@link #MEDIUM} — reserved (ADR-006) for the solver's own {@code unassignedPlayer} waitlist
 *       penalty (M6); rejected with a clear error everywhere a user could otherwise set it
 *       (custom/standard field PATCH, constraint-weight override).
 * </ul>
 */
public final class HardOrSoft {

    public static final String HARD = "HARD";
    public static final String MEDIUM = "MEDIUM";
    public static final String SOFT = "SOFT";
    public static final String INFO = "INFO";

    public static final Set<String> ALL = Set.of(HARD, MEDIUM, SOFT, INFO);

    /** What a user may set via the field-builder/constraint-weight APIs — MEDIUM excluded (ADR-006). */
    public static final Set<String> USER_SETTABLE_FOR_OPTIMIZING_FIELDS = Set.of(HARD, SOFT);

    private HardOrSoft() {
    }
}
