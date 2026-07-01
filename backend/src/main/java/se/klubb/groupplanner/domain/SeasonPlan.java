package se.klubb.groupplanner.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A season (spec §7.5), the top-level container for one or more {@link ActivityPlan}s (e.g. one
 * season "VT26" holds a "Herr" and a "Dam" activity plan).
 */
public record SeasonPlan(
        String id,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
