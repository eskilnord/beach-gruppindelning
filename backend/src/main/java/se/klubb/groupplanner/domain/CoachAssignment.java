package se.klubb.groupplanner.domain;

/**
 * A coach's placement into a {@link TrainingGroup} (spec §7.12). Unlike {@link PlayerAssignment},
 * coaches have no waitlist concept — a row only exists once a coach is actually assigned to a group
 * (a required-but-unfilled coach slot simply has no row here; see {@code solver.domain.CoachSlot}).
 * {@code source} is one of {@code imported|manual|solver|locked}.
 */
public record CoachAssignment(String id, String coachProfileId, String groupId, boolean locked, String source) {

    public static final String SOURCE_SOLVER = "solver";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_LOCKED = "locked";
}
