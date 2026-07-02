package se.klubb.groupplanner.domain;

/**
 * A physical training location (spec §7.7) — club-level, shared across every season/activity plan.
 * A default venue named "Hallen" is auto-created the first time a court is needed and none exists
 * yet (spec §12.2/docs/plan.md M5 row: "MVP-simple").
 */
public record Venue(String id, String name, String notes) {

    /** Name used for the auto-created default venue when no venue exists yet. */
    public static final String DEFAULT_VENUE_NAME = "Hallen";
}
