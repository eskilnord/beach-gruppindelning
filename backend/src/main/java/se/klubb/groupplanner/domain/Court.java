package se.klubb.groupplanner.domain;

/**
 * A court/resource at a {@link Venue} (spec §6.8/§7.8), e.g. "Bana 1". Courts auto-created by the
 * block-generation endpoint follow the naming convention {@code "Bana " + n}; courts created
 * directly via {@code POST /api/courts} may use any name. {@code active} is a club-wide flag
 * (independent of {@link TrainingBlock#active()}, which is per time-slot).
 */
public record Court(String id, String venueId, String name, boolean active, String notes) {
}
