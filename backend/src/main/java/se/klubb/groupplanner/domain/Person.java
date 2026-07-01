package se.klubb.groupplanner.domain;

import java.time.Instant;

/**
 * A person known to the club (spec §7.1) — shared across seasons and activity plans; the same
 * person can be a participant in one plan and a coach in another, or both at once.
 */
public record Person(
        String id,
        String firstName,
        String lastName,
        String displayName,
        String email,
        String phone,
        String externalId,
        boolean canBeParticipant,
        boolean canBeCoach,
        String notes,
        Instant createdAt,
        Instant updatedAt) {
}
