package se.klubb.groupplanner.importer.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse validation (not interpretation - spec §8.5/§2.2 forbid auto-interpreting free text) for the
 * "time-ish" reference columns the real file has (a {@code Tid} column mixing genuine Excel time
 * values with semi-structured Swedish shorthand like {@code "ej 21"} meaning "not at 21:00").
 *
 * <p>This recognizes a generic token grammar - not anything specific to the sample file (CLAUDE.md:
 * "Never hardcode column names from the real file"):
 *
 * <ul>
 *   <li>a bare time, {@code H(:|.)mm} optionally with seconds, e.g. {@code "18:00"}, {@code
 *       "9.30"}, {@code "18:00:00"}
 *   <li>the Swedish negation shorthand {@code "ej"} (="not") followed by an hour or a time, e.g.
 *       {@code "ej 21"}, {@code "ej 18:00"}
 *   <li>a comma/semicolon-separated list of any of the above, e.g. {@code "18:00, 19:30"}
 * </ul>
 *
 * <p>The comma is a <em>list separator only</em>, never a time's hour/minute separator (M3 review
 * finding 8): {@code "9,30"} would be inherently ambiguous between the single time 9:30 and the
 * list "9 and 30", so it reads as the list - whose second token (30) is not a valid hour - and is
 * therefore flagged "ogiltig tid - kontrollera manuellt" for the user to resolve. Write
 * {@code "9.30"} or {@code "9:30"} for a bare time.
 *
 * <p>Anything else (misspelled words, out-of-range hour/minute, a lone unstructured number) is
 * reported as invalid - spec §8.6 "ogiltiga tider" ("ogiltig tid - kontrollera manuellt").
 */
public final class SwedishTimeParser {

    private static final Pattern BARE_TIME = Pattern.compile("^(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?$");
    private static final Pattern EJ_PREFIX = Pattern.compile("^ej\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_HOUR = Pattern.compile("^(\\d{1,2})$");

    private SwedishTimeParser() {
    }

    /** True if every comma/semicolon-separated token in {@code text} matches the grammar above. */
    public static boolean isValidTimeExpression(String text) {
        if (text == null || text.isBlank()) {
            return true; // blank is not this validator's concern (see "tomma rader"/"saknat namn").
        }
        for (String token : text.split("[,;]")) {
            if (!isValidToken(token.strip())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidToken(String token) {
        if (token.isEmpty()) {
            return true;
        }
        Matcher ejMatch = EJ_PREFIX.matcher(token);
        if (ejMatch.matches()) {
            String rest = ejMatch.group(1).strip();
            return isValidHour(rest) || isValidBareTime(rest);
        }
        return isValidBareTime(token) || isValidHour(token);
    }

    private static boolean isValidBareTime(String token) {
        Matcher m = BARE_TIME.matcher(token);
        if (!m.matches()) {
            return false;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        String secondsGroup = m.group(3);
        int seconds = secondsGroup == null ? 0 : Integer.parseInt(secondsGroup);
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && seconds >= 0 && seconds <= 59;
    }

    private static boolean isValidHour(String token) {
        Matcher m = BARE_HOUR.matcher(token);
        if (!m.matches()) {
            return false;
        }
        int hour = Integer.parseInt(token);
        return hour >= 0 && hour <= 23;
    }
}
