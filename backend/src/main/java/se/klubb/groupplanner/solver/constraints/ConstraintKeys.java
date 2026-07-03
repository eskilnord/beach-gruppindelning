package se.klubb.groupplanner.solver.constraints;

import java.util.Map;
import java.util.Set;

/**
 * Stable constraint key registry (docs/design/04-solver.md §4): the single source of truth for the
 * string names used both by {@link GroupPlanConstraintProvider#defineConstraints}'s {@code
 * .asConstraint(key)} calls AND by {@code SolverInputAssembler}'s {@code ConstraintWeightOverrides}
 * construction — the same pattern already established by {@code se.klubb.groupplanner.fields
 * .ConstraintTypes}/{@code HardOrSoft}.
 *
 * <p>Where a key already exists as a seeded {@code constraint_definition} row from an earlier
 * milestone (V2), the constant reuses that exact string rather than the shorthand name used in
 * design doc's own §4 table (e.g. {@code groupMaxSizeHard}, not {@code groupMaxSize}) — see
 * backend/docs/m6a-notes.md "Deviations" for the full reconciliation between the M1-seeded 24
 * constraints and this design doc's per-constraint granularity.
 *
 * <p><b>M6b addition: all SOFT constraints.</b> {@link #COMPLEMENTS_OF} implements the verifier's
 * "complement-key fan-out" requirement (docs/design/05-solver-verification.md minor finding): the
 * empty-group complements {@code groupSizeTargetEmpty}/{@code groupMinSizeEmpty} are implemented
 * Constraint Streams code with their own {@code .asConstraint} keys, but have NO {@code
 * constraint_definition} row of their own — a user only ever sees/edits the parent row ({@code
 * groupSizeTarget}/{@code groupMinSizeSoft}), and {@code SolverInputAssembler} fans that ONE weight
 * out to both the parent and its complement key so they always move together.
 */
public final class ConstraintKeys {

    private ConstraintKeys() {
    }

    // --- HARD: satisfied by construction, no Constraint Streams code (documented, tested at the
    // model level rather than via ConstraintVerifier) ---
    public static final String GROUP_REQUIRES_TRAINING_BLOCK = "groupRequiresTrainingBlock"; // §10.2
    public static final String LOCKED_ASSIGNMENT_HARD = "lockedAssignmentHard"; // §10.23

    // --- HARD: implemented in GroupPlanConstraintProvider ---
    public static final String TRAINING_BLOCK_CAPACITY = "trainingBlockCapacity"; // §10.1
    public static final String GROUP_MAX_SIZE_HARD = "groupMaxSizeHard"; // §10.3
    public static final String TIME_AVAILABILITY_HARD = "timeAvailabilityHard"; // §10.9
    public static final String SAME_GROUP_HARD = "sameGroupHard"; // §10.11
    public static final String DIFFERENT_GROUP_HARD = "differentGroupHard"; // §10.13
    public static final String COACH_NO_OVERLAP = "coachNoOverlap"; // §10.15
    public static final String PLAYER_NO_OVERLAP = "playerNoOverlap"; // §10.16
    public static final String COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME = "coachCannotTrainAndCoachSameTime"; // §10.17
    public static final String COACH_AVAILABILITY_HARD = "coachAvailabilityHard"; // §10.18
    public static final String COACH_REQUIREMENT_HARD = "coachRequirementHard"; // §10.19
    public static final String COACH_MAX_GROUPS = "coachMaxGroups"; // spec §13.1, no § number
    public static final String COACH_WISH_REQUIRED = "coachWishRequired"; // §10.21b
    public static final String COACH_WISH_FORBIDDEN = "coachWishForbidden"; // §10.21c
    public static final String SAVED_PLAN_PERSON_BLOCKED = "savedPlanPersonBlocked"; // §10.24a
    public static final String SAVED_PLAN_COACH_BLOCKED = "savedPlanCoachBlocked"; // §10.24b
    public static final String SAVED_PLAN_COURT_BLOCKED = "savedPlanCourtBlocked"; // §10.24c

    // --- MEDIUM: the reserved waitlist penalty (never disableable/reclassifiable, ADR-006) ---
    public static final String UNASSIGNED_PLAYER = "unassignedPlayer";

    // --- SOFT: M6b additions, one code key per constraint_definition row unless noted ---
    public static final String GROUP_SIZE_TARGET = "groupSizeTarget"; // §10.4
    /** Complement of {@link #GROUP_SIZE_TARGET} — fans from the SAME db row, no row of its own. */
    public static final String GROUP_SIZE_TARGET_EMPTY = "groupSizeTargetEmpty"; // §10.4 (empty-group)
    public static final String GROUP_MIN_SIZE_SOFT = "groupMinSizeSoft"; // §10.5
    /** Complement of {@link #GROUP_MIN_SIZE_SOFT} — fans from the SAME db row, no row of its own. */
    public static final String GROUP_MIN_SIZE_EMPTY = "groupMinSizeEmpty"; // §10.5 (empty-group)
    public static final String LEVEL_BALANCE = "levelBalance"; // §10.6
    public static final String GROUP_ORDER_BY_LEVEL = "groupOrderByLevel"; // §10.7
    public static final String PREVIOUS_GROUP_CONTINUITY = "previousGroupContinuity"; // §10.8
    public static final String TIME_PREFERENCE_SOFT = "timePreferenceSoft"; // §10.10
    public static final String SAME_GROUP_SOFT = "sameGroupSoft"; // §10.12
    public static final String DIFFERENT_GROUP_SOFT = "differentGroupSoft"; // §10.14
    public static final String COACH_LEVEL_FIT = "coachLevelFit"; // §10.20
    public static final String COACH_PREFERENCE_SOFT = "coachPreferenceSoft"; // §10.21a (WANT)
    /** The seeded {@code constraint_definition} row (V2) — NOT a code key itself (no {@code
     * .asConstraint} call uses this string): design §4 splits §10.22 into two directionally-opposite
     * constraints (10.22a penalize top groups late, 10.22b reward bottom groups late), but only ONE
     * db row was ever seeded. Both {@link #LATE_TIME_TOP_GROUPS}/{@link #LATE_TIME_BOTTOM_GROUPS}
     * fan out from this ONE row's weight (a Timefold runtime constraint — verified empirically:
     * {@code penalize(weight, matchWeightFn)} throws {@code IllegalStateException} on a negative
     * matchWeight, "Check constraint provider implementation" — ruling out the signed-matchWeight
     * single-constraint trick this milestone tried first; see backend/docs/m6b-notes.md). */
    public static final String LATE_TIME_FOR_LOWER_GROUPS = "lateTimeForLowerGroups"; // §10.22 db row (not a code key)
    public static final String LATE_TIME_TOP_GROUPS = "lateTimeTopGroups"; // §10.22a (penalize)
    public static final String LATE_TIME_BOTTOM_GROUPS = "lateTimeBottomGroups"; // §10.22b (reward)
    /** New in M6b (V6 migration): rewards a {@code CoachSlot} landing on a slot the coach marked
     * {@code PREFERRED} (M5's tri-state) — the SOFT counterpart of {@code coachAvailabilityHard}. */
    public static final String COACH_PREFERRED_TIME_SLOT = "coachPreferredTimeSlot";
    /** New in WI-B (V10 migration): penalizes a {@code CoachSlot} landing on a slot the coach left
     * neutral/unlisted ("Okänd") — no explicit {@code AVAILABLE}/{@code PREFERRED}/{@code
     * UNAVAILABLE} row at all. The explicit-{@code UNAVAILABLE} case is excluded (already a HARD
     * violation via {@code coachAvailabilityHard}, never double-punished here) — see {@code
     * CoachFact.hasKnownAvailabilityAt}. */
    public static final String COACH_UNKNOWN_TIME_SLOT = "coachUnknownTimeSlot";

    /** Every constraint key {@link GroupPlanConstraintProvider} actually implements as Constraint
     * Streams code (excludes the two "satisfied by construction" keys above, which never appear in
     * {@code ConstraintWeightOverrides} because the provider never calls {@code .asConstraint} for
     * them). Used defensively by {@code SolverInputAssembler} to filter DB constraint weights down
     * to keys the solver can actually resolve, so an unimplemented/reserved key can never reach
     * {@code ConstraintWeightOverrides} and blow up the solver at build/solve time with an unknown
     * constraint name. */
    public static final Set<String> IMPLEMENTED = Set.of(
            TRAINING_BLOCK_CAPACITY,
            GROUP_MAX_SIZE_HARD,
            TIME_AVAILABILITY_HARD,
            SAME_GROUP_HARD,
            DIFFERENT_GROUP_HARD,
            COACH_NO_OVERLAP,
            PLAYER_NO_OVERLAP,
            COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME,
            COACH_AVAILABILITY_HARD,
            COACH_REQUIREMENT_HARD,
            COACH_MAX_GROUPS,
            COACH_WISH_REQUIRED,
            COACH_WISH_FORBIDDEN,
            SAVED_PLAN_PERSON_BLOCKED,
            SAVED_PLAN_COACH_BLOCKED,
            SAVED_PLAN_COURT_BLOCKED,
            UNASSIGNED_PLAYER,
            GROUP_SIZE_TARGET,
            GROUP_SIZE_TARGET_EMPTY,
            GROUP_MIN_SIZE_SOFT,
            GROUP_MIN_SIZE_EMPTY,
            LEVEL_BALANCE,
            GROUP_ORDER_BY_LEVEL,
            PREVIOUS_GROUP_CONTINUITY,
            TIME_PREFERENCE_SOFT,
            SAME_GROUP_SOFT,
            DIFFERENT_GROUP_SOFT,
            COACH_LEVEL_FIT,
            COACH_PREFERENCE_SOFT,
            LATE_TIME_TOP_GROUPS,
            LATE_TIME_BOTTOM_GROUPS,
            COACH_PREFERRED_TIME_SLOT,
            COACH_UNKNOWN_TIME_SLOT);

    /**
     * Complement/fan-out-child map (docs/design/05-solver-verification.md minor finding, generalized
     * per backend/docs/m6b-notes.md to also cover §10.22's two-directions-one-row case): a DB {@code
     * constraint_definition}/{@code constraint_weight_config} row for the map's KEY also drives the
     * applied weight of every key in its VALUE set — used by {@code SolverInputAssembler
     * .buildConstraintWeightOverrides} so a user editing one weight (e.g. "Målstorlek grupp") moves
     * both the populated-group constraint AND its empty-group complement together, with zero UI
     * surface for the complement/children. Keys absent from this map have no fan-out (empty set).
     * {@link #LATE_TIME_FOR_LOWER_GROUPS} is the one key present here that is NOT itself in {@link
     * #IMPLEMENTED} (it has no code of its own — only its two children do).
     */
    public static final Map<String, Set<String>> COMPLEMENTS_OF = Map.of(
            GROUP_SIZE_TARGET, Set.of(GROUP_SIZE_TARGET_EMPTY),
            GROUP_MIN_SIZE_SOFT, Set.of(GROUP_MIN_SIZE_EMPTY),
            LATE_TIME_FOR_LOWER_GROUPS, Set.of(LATE_TIME_TOP_GROUPS, LATE_TIME_BOTTOM_GROUPS));

    public static Set<String> complementsOf(String key) {
        return COMPLEMENTS_OF.getOrDefault(key, Set.of());
    }
}
