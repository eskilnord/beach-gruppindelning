package se.klubb.groupplanner.importer;

/**
 * Options for {@code POST /sessions/{sid}/commit} beyond the row decisions already stored on the
 * session (spec §8.3 step 8, "Spara som importmall"). {@code templateName} is required only when
 * {@code saveAsTemplate} is true.
 */
public record CommitOptions(boolean saveAsTemplate, String templateName) {

    public static CommitOptions none() {
        return new CommitOptions(false, null);
    }
}
