package se.klubb.groupplanner.suggest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, deterministic, rule-based comment parser (WP2 — "NOT AI"). Splits a comment into clauses
 * and matches each one against {@link CommentRuleLexicon#RULES} in specificity order, producing
 * {@link RawMatch}es that {@link CommentSuggestionService} then resolves against the plan's roster
 * (names) or time slots (weekday/time). No Spring, no I/O, no randomness — a pure static function
 * of its input string, so it is trivially unit-testable and can never itself be a privacy hole (it
 * never persists or transmits anything).
 *
 * <p>PRECISION OVER RECALL (spec): every ambiguity this class can detect on its own (an explicit
 * negation guard, family-exclusive matching so a more specific cue always wins over a generic one)
 * is resolved by NOT emitting a match, never by guessing.
 */
final class CommentSuggestionParser {

    private static final int MAX_WINDOW_TOKENS = 5;
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^\\s*(?:\\S+\\s*){0," + MAX_WINDOW_TOKENS + "}");
    // Review fix (MAJOR 2): a bare "[.;!?]+" splits INSIDE a decimal-style time ("18.30") because the
    // "." between the digits looks just like a sentence-ending period. A period is only a clause
    // boundary when NOT immediately followed by a digit - "18.30" keeps its "." (followed by "3");
    // a real sentence-ending period is followed by whitespace/end-of-string, never a digit, so no
    // lookbehind is needed (a trailing period after digits, e.g. "...18.30.", IS still a boundary
    // since nothing follows it).
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("\\.(?!\\d)|[;!?]+|,\\s*men\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private CommentSuggestionParser() {
    }

    /** One raw, roster/time-agnostic match: a rule fired somewhere in the comment. {@code
     *  nameWindowText} is populated only for {@link SuggestionKind#targetsParticipants()}/{@link
     *  SuggestionKind#targetsCoaches()} kinds; {@code clauseText} only for {@link
     *  SuggestionKind#targetsTimeSlots()} kinds — the caller resolves whichever one applies. */
    record RawMatch(SuggestionKind kind, int cueOffset, String matchedText, String nameWindowText, String clauseText) {
    }

    static List<RawMatch> parse(String comment) {
        List<RawMatch> matches = new ArrayList<>();
        if (comment == null || comment.isBlank()) {
            return matches;
        }
        for (Clause clause : splitClauses(comment)) {
            matches.addAll(matchClause(clause));
        }
        return matches;
    }

    private static List<RawMatch> matchClause(Clause clause) {
        List<RawMatch> out = new ArrayList<>();
        Set<Family> consumed = EnumSet.noneOf(Family.class);
        for (CommentRuleLexicon.CueRule rule : CommentRuleLexicon.RULES) {
            Family family = Family.of(rule.kind());
            if (consumed.contains(family)) {
                continue;
            }
            Matcher cueMatcher = rule.cue().matcher(clause.text());
            if (!cueMatcher.find()) {
                continue;
            }
            consumed.add(family);

            int cueStart = cueMatcher.start();
            int cueEnd = cueMatcher.end();

            if (isPlainWishKind(rule.kind()) && isNegated(clause.text(), cueStart)) {
                continue; // negation guard: AVOID/MUST-family rules already handle the explicit forms.
            }

            String nameWindowText = null;
            String clauseText = null;
            int matchedEnd = cueEnd;
            if (rule.expectsName()) {
                Matcher windowMatcher = WINDOW_PATTERN.matcher(clause.text().substring(cueEnd));
                windowMatcher.find();
                nameWindowText = windowMatcher.group().trim();
                matchedEnd = cueEnd + windowMatcher.end();
                // Review fix (MAJOR 1): a lookahead-anchored cue (e.g. COACH_WISH's "vill (?=...som
                // tränare)") ends BEFORE the name, so a negation sitting BETWEEN the cue and the name
                // (e.g. "önskar ej Erik som tränare") is never caught by isNegated's pre-cue scan -
                // it lives in the first few tokens of the window instead.
                if (isPlainWishKind(rule.kind()) && windowNegated(nameWindowText)) {
                    continue;
                }
            } else if (rule.expectsTime()) {
                clauseText = clause.text();
                matchedEnd = clause.text().length();
            }

            String matchedText = clause.text().substring(cueStart, matchedEnd).trim();
            int cueOffset = clause.startOffset() + cueStart;
            out.add(new RawMatch(rule.kind(), cueOffset, matchedText, nameWindowText, clauseText));
        }
        return out;
    }

    private static boolean isPlainWishKind(SuggestionKind kind) {
        return kind == SuggestionKind.PLAY_WITH || kind == SuggestionKind.COACH_WISH;
    }

    /** Spec: "a PLAY_WITH/COACH_WISH cue preceded within 3 tokens by 'inte|ej|aldrig|helst inte' is
     *  suppressed". Looks at the last 3 whitespace-separated tokens of the clause text BEFORE the
     *  cue's start index. */
    private static boolean isNegated(String clauseText, int cueStart) {
        String before = clauseText.substring(0, cueStart).strip();
        if (before.isEmpty()) {
            return false;
        }
        String[] tokens = before.split("\\s+");
        int from = Math.max(0, tokens.length - 3);
        for (int i = from; i < tokens.length; i++) {
            String normalized = tokens[i].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
            if (CommentRuleLexicon.NEGATION_WORDS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Review fix (MAJOR 1): checks the first 3 tokens of a post-cue name window for a negation word
     *  - covers lookahead-anchored cues (COACH_WISH) where the negation sits between the cue and the
     *  name rather than before the cue (see call site). */
    private static boolean windowNegated(String windowText) {
        if (windowText == null || windowText.isBlank()) {
            return false;
        }
        String[] tokens = windowText.split("\\s+");
        int limit = Math.min(3, tokens.length);
        for (int i = 0; i < limit; i++) {
            String normalized = tokens[i].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
            if (CommentRuleLexicon.NEGATION_WORDS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<Clause> splitClauses(String comment) {
        List<Clause> clauses = new ArrayList<>();
        Matcher boundary = CLAUSE_BOUNDARY.matcher(comment);
        int start = 0;
        while (boundary.find()) {
            addClause(clauses, comment, start, boundary.start());
            start = boundary.end();
        }
        addClause(clauses, comment, start, comment.length());
        return clauses;
    }

    private static void addClause(List<Clause> clauses, String comment, int from, int to) {
        if (from >= to) {
            return;
        }
        String raw = comment.substring(from, to);
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        int leadingWhitespace = raw.indexOf(trimmed);
        clauses.add(new Clause(trimmed, from + Math.max(leadingWhitespace, 0)));
    }

    private record Clause(String text, int startOffset) {
    }

    /** Mutually-exclusive rule groups: within one clause, only the first (most specific) rule of a
     *  family is allowed to fire — see class javadoc. */
    private enum Family {
        PLAY, COACH, TIME, NEW_TO_CLUB, LEVEL_CHANGE, INJURY_NOTE;

        static Family of(SuggestionKind kind) {
            return switch (kind) {
                case MUST_PLAY_WITH, AVOID_PLAY_WITH, PLAY_WITH -> PLAY;
                case COACH_AVOID, COACH_WISH -> COACH;
                case TIME_CANNOT, TIME_PREFER -> TIME;
                case NEW_TO_CLUB -> NEW_TO_CLUB;
                case LEVEL_CHANGE -> LEVEL_CHANGE;
                case INJURY_NOTE -> INJURY_NOTE;
            };
        }
    }
}
