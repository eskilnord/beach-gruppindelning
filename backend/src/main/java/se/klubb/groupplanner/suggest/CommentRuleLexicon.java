package se.klubb.groupplanner.suggest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The fixed, ordered cue table {@link CommentSuggestionParser} matches each comment clause
 * against (WP2 spec). Deliberately a plain static table, not configurable at runtime — every cue
 * is a hand-picked, reviewed Swedish phrase; extending it means editing this file, never loading
 * rules from data (that would blur the line with the "no AI/cloud interpretation" spec rule).
 *
 * <p>Order matters: {@link #RULES} is MOST-SPECIFIC FIRST within each family so {@link
 * CommentSuggestionParser} can take the first matching rule per clause per family (e.g. {@link
 * SuggestionKind#MUST_PLAY_WITH}/{@link SuggestionKind#AVOID_PLAY_WITH} before {@link
 * SuggestionKind#PLAY_WITH}; {@link SuggestionKind#COACH_AVOID} before {@link
 * SuggestionKind#COACH_WISH}) without a more specific cue's substring match also firing the
 * generic rule for the same span.
 */
final class CommentRuleLexicon {

    /** One cue: a {@link SuggestionKind}, the regex that must match somewhere in a clause, and
     *  whether a name/time window should be extracted after the match. */
    record CueRule(SuggestionKind kind, Pattern cue, boolean expectsName, boolean expectsTime) {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static final List<CueRule> RULES = List.of(
            // --- most specific first: MUST before PLAY_WITH, AVOID before PLAY_WITH ---------------
            new CueRule(SuggestionKind.MUST_PLAY_WITH, p("måste (få )?spela med"), true, false),
            new CueRule(SuggestionKind.MUST_PLAY_WITH, p("endast om (?=.{0,30}(med|samma grupp))"), true, false),

            new CueRule(SuggestionKind.AVOID_PLAY_WITH, p("helst inte (i )?samma grupp som"), true, false),
            new CueRule(SuggestionKind.AVOID_PLAY_WITH, p("ej (i )?samma grupp som"), true, false),
            new CueRule(SuggestionKind.AVOID_PLAY_WITH, p("fungerar inte (ihop )?med"), true, false),
            new CueRule(SuggestionKind.AVOID_PLAY_WITH, p("inte (spela )?(tillsammans )?med"), true, false),

            new CueRule(SuggestionKind.PLAY_WITH, p("spela (gärna |helst )?(tillsammans )?med"), true, false),
            new CueRule(SuggestionKind.PLAY_WITH, p("samma grupp som"), true, false),
            new CueRule(SuggestionKind.PLAY_WITH, p("tillsammans med"), true, false),
            new CueRule(SuggestionKind.PLAY_WITH, p("i grupp med"), true, false),
            new CueRule(SuggestionKind.PLAY_WITH, p("ihop med"), true, false),

            // --- coach: AVOID before WISH -----------------------------------------------------------
            // A lookahead ((?=...)), not a consuming match, for the "...som tränare" tail: the name
            // sits BETWEEN the wish verb and "som tränare", so the cue match itself must stop right
            // before the name - otherwise the post-cue name window (see CommentSuggestionParser)
            // would start AFTER "tränare" and capture nothing.
            // Review fix (MAJOR 1): "ej"/"aldrig" are just as much an explicit negation as "inte" -
            // "Vill ej ha Erik som tränare."/"Vill aldrig ha ... som tränare." must resolve to
            // COACH_AVOID, not fall through (family-consumed-first) to the plain wish rules below.
            new CueRule(SuggestionKind.COACH_AVOID, p("(inte|ej|aldrig) (?=.{0,25}som tränare)"), true, false),

            // Precision over recall: no bare "tränare[:]? " fallback rule - a lone mention of the
            // word "tränare" with no explicit wish verb is not evidence of a coach preference.
            new CueRule(SuggestionKind.COACH_WISH, p("vill (gärna )?ha (?=.{0,25}som tränare)"), true, false),
            new CueRule(SuggestionKind.COACH_WISH, p("(vill|önskar|helst) (?=.{0,25}som tränare)"), true, false),

            // --- time: CANNOT before PREFER (no real overlap risk, kept in spec order) --------------
            new CueRule(SuggestionKind.TIME_CANNOT, p("kan inte"), false, true),
            new CueRule(SuggestionKind.TIME_CANNOT, p("kan ej"), false, true),
            new CueRule(SuggestionKind.TIME_CANNOT, p("inte på"), false, true),

            new CueRule(SuggestionKind.TIME_PREFER, p("föredrar"), false, true),
            new CueRule(SuggestionKind.TIME_PREFER, p("helst på"), false, true),
            new CueRule(SuggestionKind.TIME_PREFER, p("passar bäst"), false, true),

            // --- flags ---------------------------------------------------------------------------
            new CueRule(SuggestionKind.NEW_TO_CLUB, p("ny i klubben"), false, false),
            new CueRule(SuggestionKind.NEW_TO_CLUB, p("nybörjare"), false, false),
            new CueRule(SuggestionKind.NEW_TO_CLUB, p("känner ingen"), false, false),

            new CueRule(SuggestionKind.LEVEL_CHANGE, p("gå (ner|ned|upp) (en )?nivå"), false, false),
            new CueRule(SuggestionKind.LEVEL_CHANGE, p("lättare grupp"), false, false),
            new CueRule(SuggestionKind.LEVEL_CHANGE, p("svårare grupp"), false, false),
            new CueRule(SuggestionKind.LEVEL_CHANGE, p("mer tävlingsinriktad"), false, false),

            new CueRule(SuggestionKind.INJURY_NOTE, p("skada"), false, false),
            new CueRule(SuggestionKind.INJURY_NOTE, p("ont i"), false, false),
            new CueRule(SuggestionKind.INJURY_NOTE, p("rehab"), false, false),
            new CueRule(SuggestionKind.INJURY_NOTE, p("opererad"), false, false));

    /** Weekday cue words, in {@link java.time.DayOfWeek} order — checked as whole-word,
     *  case-insensitive substrings of the post-cue window text. */
    static final List<WeekdayCue> WEEKDAYS = List.of(
            new WeekdayCue(p("\\bmåndag(ar)?\\b"), "MONDAY"),
            new WeekdayCue(p("\\btisdag(ar)?\\b"), "TUESDAY"),
            new WeekdayCue(p("\\bonsdag(ar)?\\b"), "WEDNESDAY"),
            new WeekdayCue(p("\\btorsdag(ar)?\\b"), "THURSDAY"),
            new WeekdayCue(p("\\bfredag(ar)?\\b"), "FRIDAY"),
            new WeekdayCue(p("\\blördag(ar)?\\b"), "SATURDAY"),
            new WeekdayCue(p("\\bsöndag(ar)?\\b"), "SUNDAY"));

    /** Negation words that suppress a {@link SuggestionKind#PLAY_WITH}/{@link
     *  SuggestionKind#COACH_WISH} match when found within the preceding 3 tokens (spec: "a PLAY_WITH/
     *  COACH_WISH cue preceded within 3 tokens by 'inte|ej|aldrig|helst inte' is suppressed") OR
     *  within the first few tokens of the post-cue name window (review fix MAJOR 1: a lookahead-
     *  anchored cue like COACH_WISH's ends BEFORE the name, so a negation sitting between the cue and
     *  the name - e.g. "önskar ej Erik som tränare" - is never "before the cue" but is very much
     *  inside the window {@link CommentSuggestionParser} extracts). */
    static final List<String> NEGATION_WORDS = List.of("inte", "ej", "aldrig");

    /** Explicit-time extraction (review fix MAJORS 3/4, moved out of a single lookup-only pattern):
     *  a bare 1-2 digit number is only time-like when it carries minutes ({@code "18:30"}/{@code
     *  "18.30"}), is prefixed by {@code "kl"}/{@code "klockan"}, or is directly governed by a
     *  before/after preposition ({@code "efter 19"}) - never a bare number on its own (spec: "bortrest
     *  till 18 augusti" must never be read as 18:00). {@link #TIME_AFTER}/{@link #TIME_BEFORE} both
     *  capture the hour in group 1 and the optional minutes in group 2; {@link #TIME_WITH_MINUTES}
     *  requires minutes (group 2); {@link #TIME_KL_PREFIX} requires the "kl(ockan)?" prefix (minutes
     *  optional, group 2). */
    static final Pattern TIME_AFTER = p("\\b(?:efter|från och med|från)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b");
    static final Pattern TIME_BEFORE = p("\\b(?:före|innan|fram till)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b");
    static final Pattern TIME_WITH_MINUTES = p("\\b(\\d{1,2})[:.](\\d{2})\\b");
    static final Pattern TIME_KL_PREFIX = p("\\bkl(?:ockan)?\\.?\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\b");

    record WeekdayCue(Pattern pattern, String dayOfWeek) {
    }

    private CommentRuleLexicon() {
    }
}
