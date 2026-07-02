package se.klubb.groupplanner.domain;

/**
 * A participant's placement (or lack thereof) into a {@code training_group} (spec §7.11). {@code
 * groupId == null} means unassigned/waitlisted. {@code source} is one of {@code
 * imported|manual|solver|locked}. Full assignment CRUD (moves, locks) arrives with the solver
 * milestones (M6+); the import wizard (M3) only ever creates the initial {@code source=imported},
 * {@code groupId=null} row so a freshly-imported participant is visibly "awaiting placement".
 */
public record PlayerAssignment(String id, String participantProfileId, String groupId, boolean locked, String source) {

    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_SOLVER = "solver";
    public static final String SOURCE_LOCKED = "locked";
}
