package se.klubb.groupplanner.domain;

import java.time.Instant;

/**
 * One category's (e.g. "Herr" or "Dam") training-group plan within a {@link SeasonPlan} (spec
 * §7.6). {@code status} is one of {@code draft|saved|locked|published|archived}.
 */
public record ActivityPlan(
        String id,
        String seasonPlanId,
        String name,
        String category,
        String status,
        Integer defaultGroupTargetSize,
        Integer defaultGroupMinSize,
        Integer defaultGroupMaxSize,
        Instant createdAt,
        Instant updatedAt) {
}
