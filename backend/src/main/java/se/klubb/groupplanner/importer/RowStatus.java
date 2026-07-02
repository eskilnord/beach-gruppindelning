package se.klubb.groupplanner.importer;

/** Row-level validation outcome (spec §8.6). */
public enum RowStatus {
    OK,
    /** Informational - still imported by default unless the user overrides the decision. */
    WARN,
    /** Auto-excluded from import by default (spec §8.6 "saknat namn"/"tomma rader"), user can override. */
    SKIP
}
