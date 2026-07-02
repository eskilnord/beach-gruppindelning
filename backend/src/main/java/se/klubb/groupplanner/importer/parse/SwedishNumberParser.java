package se.klubb.groupplanner.importer.parse;

/**
 * Locale-aware numeric parsing for text-typed cells (docs/plan.md red-team correction: "locale-aware
 * parsing (`1 234,5`, NBSP thousands) only for text-typed cells" - NUMERIC/DATE xlsx cells already
 * carry a typed {@link Double}/{@link java.time.LocalDate} and never go through this class).
 *
 * <p>Handles Swedish-locale formatted numbers: {@code ,} as decimal separator, a plain space or a
 * non-breaking space (what Excel actually emits) as the thousands separator, and plain {@code
 * .}-decimal / unseparated numbers as a fallback so plain CSV exports still parse.
 */
public final class SwedishNumberParser {

    /** U+00A0 NO-BREAK SPACE - the thousands separator Excel actually writes for sv-SE numbers. */
    private static final char NBSP = ' ';
    /** U+202F NARROW NO-BREAK SPACE - used by some locales/newer Excel versions for the same purpose. */
    private static final char NARROW_NBSP = ' ';

    /**
     * The only shapes accepted after separator normalization. {@code Double.parseDouble} alone is
     * far too permissive for user data - it accepts {@code "NaN"}, {@code "Infinity"}, hex floats
     * ({@code "0x1p4"}) and type-suffixed literals ({@code "5f"}), none of which a registration
     * sheet legitimately contains; they must flag as "ogiltiga tal" instead (M3 review finding 6).
     */
    private static final java.util.regex.Pattern PLAIN_NUMBER =
            java.util.regex.Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    private SwedishNumberParser() {
    }

    /**
     * Parses a Swedish-locale-formatted number, e.g. {@code "1 234,5"} or the NBSP-thousands
     * variant, or plain {@code "940"} / {@code "12.5"}. Returns {@code null} if the text does not
     * look like a number at all (spec §8.6 "ogiltiga tal" - callers turn a {@code null} result into
     * a validation warning, they never throw here).
     */
    public static Double parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Strip thousands separators: plain spaces and the non-breaking space variants Excel uses.
        String withoutThousands = trimmed
                .replace(String.valueOf(NBSP), "")
                .replace(String.valueOf(NARROW_NBSP), "")
                .replace(" ", "");
        // Swedish decimal comma -> '.'; a lone '.' is left alone (already a valid Java decimal).
        String normalized = withoutThousands.indexOf(',') >= 0
                ? withoutThousands.replace(".", "").replace(',', '.')
                : withoutThousands;
        if (!PLAIN_NUMBER.matcher(normalized).matches()) {
            return null;
        }
        try {
            double value = Double.parseDouble(normalized);
            // Belt and braces with the regex above: never return NaN/Infinity to callers.
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
