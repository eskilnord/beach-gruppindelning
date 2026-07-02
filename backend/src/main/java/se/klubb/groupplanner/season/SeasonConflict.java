package se.klubb.groupplanner.season;

import java.util.List;

/** Response shape for {@code GET /api/seasons/{seasonId}/conflicts} (docs/design/04-solver.md §14.2,
 * spec §19.2). {@code type} is one of {@link #PERSON_DOUBLE_BOOKED}, {@link
 * #COACH_PLAYS_WHILE_COACHING}, {@link #COURT_DOUBLE_BOOKED}; {@code severity} is always {@code
 * "WARNING"} (pure reporting — this service never blocks anything by itself, unlike the in-solver
 * {@code savedPlan*} HARD constraints). */
public record SeasonConflict(
        String type,
        String severity,
        String personId,
        String personName,
        String courtId,
        String courtName,
        List<ConflictUsage> usages) {

    public static final String PERSON_DOUBLE_BOOKED = "PERSON_DOUBLE_BOOKED";
    public static final String COACH_PLAYS_WHILE_COACHING = "COACH_PLAYS_WHILE_COACHING";
    public static final String COURT_DOUBLE_BOOKED = "COURT_DOUBLE_BOOKED";
    public static final String SEVERITY_WARNING = "WARNING";

    public record ConflictUsage(String planId, String planName, String groupName, String time) {
    }
}
