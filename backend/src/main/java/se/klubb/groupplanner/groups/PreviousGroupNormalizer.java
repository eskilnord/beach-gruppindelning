package se.klubb.groupplanner.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a participant's free-text {@code previous_group_name} (e.g. {@code "Torsdag Herr 1
 * (Vårtermin 2025) |Torsdag Herr 2"}) into a {@link PreviousGroupRef}. Pure — no repo/domain
 * dependency, so it can be unit tested in isolation and reused from importer, solver-assembly and
 * exporter code alike (wiring into any of those is a later milestone, not this one).
 *
 * <p><b>Algorithm</b> (see the B1 spec table for the full worked examples):
 * <ol>
 *   <li>Unicode whitespace (nbsp, thin/narrow-no-break spaces, the BOM/zero-width-no-break-space,
 *       and every {@code \p{Zs}} character — as commonly pasted from spreadsheet exports) is
 *       normalized to a regular space before anything else happens.
 *   <li>Split the (whitespace-normalized) raw text on {@code |}, trim each segment, drop blank
 *       segments. No segments left -&gt; {@code null}.
 *   <li><b>Pick the newest segment.</b> Every term ({@code vårtermin}/{@code hösttermin}/{@code vt}/
 *       {@code ht} + a 2- or 4-digit year, found anywhere in the segment, possibly more than once)
 *       is parsed from every segment and converted to a {@code termKey}; a segment's key is the
 *       MAXIMUM {@code termKey} among all of its term occurrences. The segment with the highest
 *       {@code termKey} wins; a tie keeps the leftmost of the tied segments. <b>Positional
 *       fallback:</b> if NO segment carries a parseable term, the leftmost segment is chosen — this
 *       assumes (per the product's typical export format) that when no term label disambiguates
 *       order, the newest entry is usually written first. This is a heuristic, not a guarantee.
 *   <li>From the chosen segment, the term suffix is stripped ONLY when it appears in a trailing
 *       position: a trailing {@code (term)} parenthetical, or a bare trailing {@code , term} /
 *       {@code - term} / {@code term} (whitespace-only separator) suffix. A non-term parenthetical
 *       (e.g. {@code "(nybörjare)"}) is left in place — information is never discarded. When the
 *       segment's highest-key term is NOT in a trailing position (leading or mid-string), {@code
 *       termLabel}/{@code termKey} are still populated from it, and every term occurrence is removed
 *       from the text used for {@code groupOrder}/{@code canonicalName} so a leading/mid-string term
 *       never poisons them — UNLESS the segment consists of nothing but that term, in which case
 *       removing it would leave nothing and the (unmodified) legacy positional heuristic applies
 *       instead (see the groupOrder caveat below). {@code rawDisplay} is always the chosen segment
 *       exactly as-is (term suffix included — it is display text, not the canonical form).
 *   <li>{@code groupOrder} is extracted from the term-stripped text; the first of these three rules
 *       that matches AND yields a value in {@code [1, 60]} wins: (a) a trailing standalone 1-2 digit
 *       integer, optionally followed by a single trailing letter (e.g. {@code "1B"}); (b) {@code
 *       grupp}/{@code nivå}/{@code niva}/{@code lag} followed by a 1-2 digit integer; (c) a leading
 *       1-2 digit integer. A rule that matches but yields a value outside {@code [1, 60]} does NOT
 *       short-circuit — the next rule is tried (e.g. a trailing "99" falls through from rule (a) to
 *       rule (b)/(c)). If rule (a) still fails after being tried directly, it is retried once more
 *       against the text with one trailing non-term {@code (...)} parenthetical removed (ordinal
 *       extraction only — {@code canonicalName} always keeps the parenthetical). <b>Heuristic
 *       limits:</b> this is purely positional/lexical, not semantic — e.g. {@code "Bana 1"} parses
 *       as order 1 (rule (a) has no notion that "Bana" means "lane", not a category qualifier), and
 *       a segment that is nothing but a bare 2-digit-year term with no other text (e.g. a lone
 *       {@code "VT25"}) parses as order 25 (the legacy-heuristic carve-out described above treats
 *       the year digits as a trailing ordinal since there is nothing else to derive one from). These
 *       are accepted, documented limitations, not bugs.
 *   <li>{@code canonicalName} = the term-stripped text, lowercased ({@link Locale#ROOT}), with
 *       {@code . , ; : _ - –} replaced by a space, whitespace collapsed, trimmed. Swedish å/ä/ö are
 *       preserved (no ASCII folding).
 *   <li>{@code categoryPart} = {@code canonicalName} with its trailing ordinal removed, but only
 *       when that ordinal was found via rule (a) above; {@code null} otherwise, and {@code null}
 *       when the remainder would be empty.
 * </ol>
 */
public final class PreviousGroupNormalizer {

    // Any Unicode "space separator" plus the BOM/zero-width-no-break-space (category Cf, not Zs,
    // so it needs to be listed explicitly). Spreadsheet exports routinely paste nbsp ( ),
    // narrow no-break space ( ), thin space ( ), etc. in place of a plain space.
    private static final Pattern UNICODE_WHITESPACE = Pattern.compile("[\\p{Zs}\\uFEFF]");

    // Matches a term anywhere in a string: keyword + optional separator + 2- or 4-digit year.
    // Year alternation is 4-digit-first: (\d{2}|\d{4}) would greedily accept just the first two
    // digits of a 4-digit year (regex alternation does not prefer the longer branch), corrupting
    // e.g. "2025" into "20". \b before the keyword alternation stops it matching inside an unrelated
    // word (e.g. the "ht" in "Night"); (?!\d) after the year stops a typo'd/truncated year (e.g.
    // "20255" or "202") from matching a valid-looking prefix/suffix of itself.
    private static final Pattern TERM_ANYWHERE = Pattern.compile(
            "(?iu)\\b(v[åa]rtermin|h[öo]sttermin|vt|ht)\\s*[-–]?\\s*(\\d{4}|\\d{2})(?!\\d)");

    // Trailing "(term)" parenthetical - captures the term text without the parentheses.
    private static final Pattern TRAILING_PAREN_TERM = Pattern.compile(
            "(?iu)\\s*\\(\\s*(\\b(?:v[åa]rtermin|h[öo]sttermin|vt|ht)\\s*[-–]?\\s*(?:\\d{4}|\\d{2})(?!\\d))\\s*\\)\\s*$");

    // Bare trailing ", term" / "- term" / whitespace-only-separated "term" suffix (no parentheses).
    private static final Pattern TRAILING_BARE_TERM = Pattern.compile(
            "(?iu)(?:[,\\-–]\\s*|\\s+)(\\b(?:v[åa]rtermin|h[öo]sttermin|vt|ht)\\s*[-–]?\\s*(?:\\d{4}|\\d{2})(?!\\d))\\s*$");

    // groupOrder rule (a): trailing standalone 1-2 digit integer, not part of a longer digit run,
    // optionally followed by a single trailing letter (e.g. "1B"). Applied after term stripping, so
    // a term's year digits ("HT25") can never masquerade as this ordinal+letter.
    private static final Pattern TRAILING_ORDER =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[a-zA-ZåäöÅÄÖ]?\\s*$");

    // A single trailing non-term parenthetical, e.g. "(nybörjare)" - used only as a fallback input
    // for rule (a) when the ordinal isn't found otherwise; never used for canonicalName.
    private static final Pattern TRAILING_NON_TERM_PAREN = Pattern.compile("\\([^)]*\\)\\s*$");

    // groupOrder rule (b): grupp/nivå/niva/lag followed by a 1-2 digit integer. (?iu) so Swedish
    // å/ä/ö case-fold correctly - plain (?i) is ASCII-only and misses e.g. "NIVÅ".
    private static final Pattern KEYWORD_ORDER =
            Pattern.compile("(?iu)\\b(?:grupp|niv[åa]|lag)\\s*(\\d{1,2})\\b");

    // groupOrder rule (c): leading 1-2 digit integer.
    private static final Pattern LEADING_ORDER = Pattern.compile("^\\s*(\\d{1,2})\\b");

    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:_\\-–]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PreviousGroupNormalizer() {
    }

    public static PreviousGroupRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = normalizeWhitespace(raw);

        List<String> segments = new ArrayList<>();
        for (String part : normalized.split("\\|", -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        if (segments.isEmpty()) {
            return null;
        }

        int chosenIndex = 0;
        Integer bestKey = null;
        for (int i = 0; i < segments.size(); i++) {
            Integer key = detectTermKey(segments.get(i));
            if (key != null && (bestKey == null || key > bestKey)) {
                bestKey = key;
                chosenIndex = i;
            }
        }
        String chosen = segments.get(chosenIndex);

        String rawDisplay = chosen;
        String termLabel = null;
        String withoutTerm = chosen;

        Matcher paren = TRAILING_PAREN_TERM.matcher(chosen);
        Matcher bare = TRAILING_BARE_TERM.matcher(chosen);
        if (paren.find()) {
            termLabel = paren.group(1).trim();
            withoutTerm = chosen.substring(0, paren.start()).trim();
        } else if (bare.find()) {
            termLabel = bare.group(1).trim();
            withoutTerm = chosen.substring(0, bare.start()).trim();
        } else {
            TermMatch best = bestTermMatch(chosen);
            if (best != null) {
                termLabel = best.text();
                String stripped = TERM_ANYWHERE.matcher(chosen).replaceAll("").trim();
                if (!stripped.isEmpty()) {
                    withoutTerm = stripped;
                }
                // else: the segment is nothing but the term itself - leave withoutTerm as the
                // original chosen text so the documented legacy positional heuristic still applies.
            }
        }

        Integer termKey = null;
        if (termLabel != null) {
            Matcher tm = TERM_ANYWHERE.matcher(termLabel);
            if (tm.find()) {
                termKey = termKeyFromMatch(tm.group(1), tm.group(2));
            }
        }

        OrderExtraction order = extractGroupOrder(withoutTerm);
        if (order.order() == null) {
            Matcher trailingParen = TRAILING_NON_TERM_PAREN.matcher(withoutTerm);
            if (trailingParen.find()) {
                String withoutParen = withoutTerm.substring(0, trailingParen.start()).trim();
                if (!withoutParen.isEmpty()) {
                    OrderExtraction fallback = extractGroupOrder(withoutParen);
                    if (fallback.order() != null) {
                        order = fallback;
                    }
                }
            }
        }
        String canonicalName = canonicalizeText(withoutTerm);

        String categoryPart = null;
        if (order.order() != null && order.viaRuleA()) {
            Matcher cm = TRAILING_ORDER.matcher(canonicalName);
            if (cm.find()) {
                String stripped = canonicalName.substring(0, cm.start()).trim();
                categoryPart = stripped.isEmpty() ? null : stripped;
            }
        }

        return new PreviousGroupRef(rawDisplay, canonicalName, categoryPart, order.order(), termLabel, termKey);
    }

    /**
     * The Swedish "kan inte tolkas" warning sentence for a raw previous-group value, or {@code null}
     * when the value is blank or parses to a {@link PreviousGroupRef#groupOrder()} successfully (B5).
     * Shared by the importer's row-validation warning ({@code ImportValidationService}) and the
     * participant API's derived {@code previousGroupParseWarning} field ({@code
     * ParticipantProfileController}) so the exact wording never drifts between the two surfaces.
     */
    public static String parseWarningSv(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        PreviousGroupRef ref = parse(raw);
        if (ref != null && ref.groupOrder() != null) {
            return null;
        }
        // v0.6.0 audit-fix B8: names the concrete consequence (no continuity benefit) instead of the
        // more abstract "kontinuitet används inte", and tells the admin exactly where to fix it by
        // hand (Deltagare) rather than leaving a dead end.
        return "Tidigare grupp \"" + raw + "\" kunde inte tolkas som en grupp — den här deltagaren får ingen "
                + "fördel av sin tidigare grupp. Du kan fylla i det för hand under Deltagare.";
    }

    /**
     * Applies the same canonicalization used for a parsed {@link PreviousGroupRef#canonicalName()}
     * to an arbitrary plain group name (e.g. a generated plan group's {@code "<category> N"} name).
     * Public so callers wiring this module up to a plan's groups (a later milestone) can precompute
     * every candidate's canonical name once up front instead of re-canonicalizing per match attempt
     * — {@link PreviousGroupMatcher#match} currently canonicalizes per-call internally, which is
     * fine for now given the expected list sizes, but a caller with many refs to match against the
     * same plan may prefer to precompute.
     */
    public static String canonicalizeName(String text) {
        return canonicalizeText(text);
    }

    /** Package-visible so {@link PreviousGroupMatcher} can canonicalize plain group names the same way. */
    static String canonicalizeText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String replaced = PUNCTUATION.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(replaced).replaceAll(" ").trim();
    }

    private static String normalizeWhitespace(String text) {
        return UNICODE_WHITESPACE.matcher(text).replaceAll(" ");
    }

    private static Integer detectTermKey(String segment) {
        Matcher m = TERM_ANYWHERE.matcher(segment);
        Integer best = null;
        while (m.find()) {
            Integer key = termKeyFromMatch(m.group(1), m.group(2));
            if (best == null || key > best) {
                best = key;
            }
        }
        return best;
    }

    /** Finds the term occurrence with the highest {@code termKey} anywhere in the text. */
    private static TermMatch bestTermMatch(String text) {
        Matcher m = TERM_ANYWHERE.matcher(text);
        TermMatch best = null;
        while (m.find()) {
            Integer key = termKeyFromMatch(m.group(1), m.group(2));
            if (best == null || key > best.key()) {
                best = new TermMatch(m.group().trim(), key);
            }
        }
        return best;
    }

    private static Integer termKeyFromMatch(String keyword, String yearDigits) {
        int year = Integer.parseInt(yearDigits);
        if (yearDigits.length() == 2) {
            year += 2000;
        }
        boolean autumn = Character.toLowerCase(keyword.charAt(0)) == 'h';
        return year * 2 + (autumn ? 1 : 0);
    }

    private static OrderExtraction extractGroupOrder(String text) {
        Matcher a = TRAILING_ORDER.matcher(text);
        if (a.find()) {
            Integer value = validOrNull(a.group(1));
            if (value != null) {
                return new OrderExtraction(value, true);
            }
        }
        Matcher b = KEYWORD_ORDER.matcher(text);
        if (b.find()) {
            return new OrderExtraction(validOrNull(b.group(1)), false);
        }
        Matcher c = LEADING_ORDER.matcher(text);
        if (c.find()) {
            return new OrderExtraction(validOrNull(c.group(1)), false);
        }
        return new OrderExtraction(null, false);
    }

    private static Integer validOrNull(String digits) {
        int value = Integer.parseInt(digits);
        return (value >= 1 && value <= 60) ? value : null;
    }

    private record OrderExtraction(Integer order, boolean viaRuleA) {
    }

    private record TermMatch(String text, Integer key) {
    }
}
