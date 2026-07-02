package se.klubb.groupplanner.season;

import se.klubb.groupplanner.common.time.TimeKey;

/** One (person, time[, court]) commitment inside a season, gathered from a plan's CURRENT persisted
 * assignments regardless of the plan's status (design §6.3). {@code kind} is {@link #KIND_PLAYER}
 * or {@link #KIND_COACH}. */
record ResourceUsage(
        String activityPlanId,
        String activityPlanName,
        String kind,
        String personId,
        String groupName,
        TimeKey timeKey,
        String timeLabel,
        String courtId,
        String courtName) {

    static final String KIND_PLAYER = "PLAYER";
    static final String KIND_COACH = "COACH";
}
