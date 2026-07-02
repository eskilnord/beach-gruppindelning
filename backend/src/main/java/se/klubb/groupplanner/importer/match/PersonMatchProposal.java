package se.klubb.groupplanner.importer.match;

/**
 * A candidate match between an imported row and an existing (or another in-file) person (spec
 * §8.7). {@code confidence} is in {@code [0.0, 1.0]}; {@code matchBasis} explains why the two were
 * proposed as the same person, for the wizard UI to show the user.
 */
public record PersonMatchProposal(String existingPersonId, MatchBasis matchBasis, double confidence) {

    public enum MatchBasis {
        /** Same {@code person.external_id} (member id from the source system) - the strongest basis. */
        EXTERNAL_ID_EXACT,
        EMAIL_EXACT,
        PHONE_EXACT,
        NAME_EXACT,
        NAME_SIMILAR
    }
}
