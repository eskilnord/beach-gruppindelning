package se.klubb.groupplanner.importer;

import java.util.regex.Pattern;
import se.klubb.groupplanner.groups.PreviousGroupNormalizer;
import se.klubb.groupplanner.groups.PreviousGroupRef;

/**
 * Normalizes raw Excel/CSV cell text for targets that council files commonly mis-format, so
 * imported data is usable without hand-editing: Excel float-formatted member ids ({@code "1924.0"}),
 * and pipe-concatenated previous-group history ({@code "Grupp A (VT25) |Grupp B ..."}).
 *
 * <p>Kept free of file-specific column names (CLAUDE.md) - only value shapes.
 */
public final class ImportedValueNormalizer {

    /** Excel often stores whole numbers as floats; DataFormatter then yields {@code "1924.0"}. */
    private static final Pattern FLOAT_WHOLE_NUMBER = Pattern.compile("^-?\\d+\\.0+$");

    private ImportedValueNormalizer() {
    }

    /**
     * Strips trailing {@code .0+} from float-formatted whole numbers so member ids round-trip as
     * clean integers. Other text (including genuine decimals like {@code "12.5"}) is left as-is
     * after a strip.
     */
    public static String externalId(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (FLOAT_WHOLE_NUMBER.matcher(stripped).matches()) {
            return stripped.substring(0, stripped.indexOf('.'));
        }
        return stripped;
    }

    /**
     * Picks the newest segment of a pipe-concatenated previous-group history (common in council
     * exports that append older terms after {@code |}), delegating to {@link
     * PreviousGroupNormalizer#parse(String)} - see its javadoc for the newest-term-wins/positional-
     * fallback algorithm. {@code null} in, or nothing but blank/pipe segments, yields {@code null}.
     */
    public static String previousGroupName(String raw) {
        PreviousGroupRef ref = PreviousGroupNormalizer.parse(raw);
        return ref != null ? ref.rawDisplay() : null;
    }
}
