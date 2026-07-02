package se.klubb.groupplanner.importer;

import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * The user's final decision for one data row (spec §8.7): create a new person, link to an existing
 * one, or skip the row entirely. Set via {@code PUT /sessions/{sid}/decisions}; defaults are
 * derived from {@link RowValidationResult#status()} when no explicit decision was made (see
 * {@code ImportSession#effectiveDecision}).
 */
public record RowDecision(Action action, String personId) {

    public enum Action {
        CREATE_NEW,
        MATCH_EXISTING,
        SKIP
    }

    public RowDecision {
        if (action == Action.MATCH_EXISTING && (personId == null || personId.isBlank())) {
            throw new BadRequestException("MATCH_EXISTING decision requires a personId");
        }
        if (action != Action.MATCH_EXISTING && personId != null) {
            throw new BadRequestException("personId is only valid for MATCH_EXISTING decisions");
        }
    }

    public static RowDecision createNew() {
        return new RowDecision(Action.CREATE_NEW, null);
    }

    public static RowDecision skip() {
        return new RowDecision(Action.SKIP, null);
    }

    public static RowDecision matchExisting(String personId) {
        return new RowDecision(Action.MATCH_EXISTING, personId);
    }
}
