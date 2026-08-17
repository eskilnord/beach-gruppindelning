package se.klubb.groupplanner.suggest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.klubb.groupplanner.importer.match.JaroWinkler;
import se.klubb.groupplanner.suggest.CommentSuggestion.TargetCandidate;

/**
 * Resolves a name spotted in a comment's post-cue window text against a candidate roster
 * (participants for {@link SuggestionKind#targetsParticipants()} kinds, coaches for {@link
 * SuggestionKind#targetsCoaches()} kinds — the caller decides which list to pass in and excludes
 * the participant themself). Pure, no Spring — reuses {@link JaroWinkler}, the same hand-rolled
 * similarity metric the M3 import wizard's person matcher already uses (spec §8.7), rather than
 * inventing a second one.
 *
 * <p>PRECISION OVER RECALL (WP2 spec): every threshold below is a hard cutoff picked to avoid a
 * wrong suggestion, not to maximize how often a name resolves — more than 3 plausible candidates,
 * or none at all, always means "say nothing" ({@link #resolve} returning {@code null}), never a
 * best-effort guess.
 */
final class RosterNameResolver {

    // Review fix (minor 6): matches PersonMatcher.NAME_SIMILARITY_THRESHOLD (spec §8.7's "Jaro-Winkler
    // >= 0.92 on 'förnamn efternamn'") — this project's ALREADY-established full-name similarity bar,
    // rather than a separately-invented, looser 0.85 for the exact same kind of comparison.
    private static final double FULL_NAME_THRESHOLD = 0.92;
    private static final double FULL_NAME_TIE_MARGIN = 0.03;
    private static final double FIRST_NAME_CANDIDATE_THRESHOLD = 0.90;
    private static final double FIRST_NAME_UNIQUE_THRESHOLD = 0.95;
    private static final int MAX_CANDIDATE_TOKENS = 3;
    private static final int MAX_REPORTED_CANDIDATES = 3;

    private static final Pattern CAPITALIZED_TOKEN =
            Pattern.compile("^\\p{Lu}[\\p{L}'-]*$", Pattern.UNICODE_CASE);

    private RosterNameResolver() {
    }

    /** One roster member a name could resolve to — deliberately narrow (id + the two name parts
     *  used for matching), so a caller can build this from either a {@code Person} row (participants)
     *  or a {@code Person} row joined through {@code coach_profile} (coaches). */
    record RosterEntry(String id, String displayName, String firstName, String lastName) {
    }

    record Resolution(List<TargetCandidate> candidates, CommentSuggestion.Confidence confidence) {
    }

    /** {@code null} means "drop the suggestion entirely" (no candidate string found in the window,
     *  or resolution was ambiguous/absent beyond the thresholds above). */
    static Resolution resolve(String windowText, List<RosterEntry> roster) {
        String candidate = extractCandidate(windowText);
        if (candidate == null || roster.isEmpty()) {
            return null;
        }
        String[] tokens = candidate.split("\\s+");
        return tokens.length >= 2 ? resolveFullName(candidate, roster) : resolveFirstName(candidate, roster);
    }

    private static Resolution resolveFullName(String candidate, List<RosterEntry> roster) {
        String candidateNorm = normalize(candidate);
        List<Scored> scored = new ArrayList<>();
        for (RosterEntry entry : roster) {
            double score = JaroWinkler.similarity(candidateNorm, normalize(fullName(entry)));
            if (score >= FULL_NAME_THRESHOLD) {
                scored.add(new Scored(entry, score));
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        double best = scored.get(0).score();
        List<Scored> withinMargin = scored.stream().filter(s -> best - s.score() <= FULL_NAME_TIE_MARGIN).toList();
        CommentSuggestion.Confidence confidence =
                withinMargin.size() == 1 ? CommentSuggestion.Confidence.HIGH : CommentSuggestion.Confidence.UNCERTAIN;
        List<TargetCandidate> candidates = withinMargin.stream()
                .limit(MAX_REPORTED_CANDIDATES)
                .map(Scored::toTarget)
                .toList();
        return new Resolution(candidates, confidence);
    }

    private static Resolution resolveFirstName(String candidate, List<RosterEntry> roster) {
        String candidateNorm = normalize(candidate);
        List<Scored> scored = new ArrayList<>();
        for (RosterEntry entry : roster) {
            if (entry.firstName() == null || entry.firstName().isBlank()) {
                continue;
            }
            double score = JaroWinkler.similarity(candidateNorm, normalize(entry.firstName()));
            if (score >= FIRST_NAME_CANDIDATE_THRESHOLD) {
                scored.add(new Scored(entry, score));
            }
        }
        if (scored.isEmpty() || scored.size() > MAX_REPORTED_CANDIDATES) {
            return null;
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (scored.size() == 1) {
            return scored.get(0).score() >= FIRST_NAME_UNIQUE_THRESHOLD
                    ? new Resolution(List.of(scored.get(0).toTarget()), CommentSuggestion.Confidence.HIGH)
                    : null;
        }
        return new Resolution(scored.stream().map(Scored::toTarget).toList(), CommentSuggestion.Confidence.UNCERTAIN);
    }

    /** Longest contiguous run (capped at {@value #MAX_CANDIDATE_TOKENS} tokens) of capitalized words
     *  in the window text — a first-and-last name pair, or a single first name. {@code null} if no
     *  capitalized token exists at all. */
    static String extractCandidate(String windowText) {
        if (windowText == null || windowText.isBlank()) {
            return null;
        }
        String[] rawTokens = windowText.trim().split("\\s+");
        List<String> cleaned = new ArrayList<>(rawTokens.length);
        for (String raw : rawTokens) {
            String stripped = raw.replaceAll("^[^\\p{L}]+|[^\\p{L}'-]+$", "");
            cleaned.add(stripped);
        }

        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < cleaned.size(); i++) {
            String token = cleaned.get(i);
            boolean capitalized = !token.isEmpty() && isCapitalizedWord(token);
            if (capitalized) {
                if (curLen == 0) {
                    curStart = i;
                }
                curLen++;
            } else {
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                }
                curLen = 0;
            }
        }
        if (curLen > bestLen) {
            bestLen = curLen;
            bestStart = curStart;
        }
        if (bestStart == -1 || bestLen == 0) {
            return null;
        }
        int takeLen = Math.min(bestLen, MAX_CANDIDATE_TOKENS);
        StringBuilder sb = new StringBuilder();
        for (int i = bestStart; i < bestStart + takeLen; i++) {
            if (i > bestStart) {
                sb.append(' ');
            }
            sb.append(cleaned.get(i));
        }
        return sb.toString();
    }

    private static boolean isCapitalizedWord(String token) {
        Matcher matcher = CAPITALIZED_TOKEN.matcher(token);
        return matcher.matches();
    }

    private static String fullName(RosterEntry entry) {
        boolean hasFirstOrLast = notBlank(entry.firstName()) || notBlank(entry.lastName());
        if (hasFirstOrLast) {
            return (nullToEmpty(entry.firstName()) + " " + nullToEmpty(entry.lastName())).strip();
        }
        return nullToEmpty(entry.displayName());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private record Scored(RosterEntry entry, double score) {
        /** {@code applied} always starts {@code false} here - this class has no notion of the
         *  participant's current field values; {@link CommentSuggestionService} fills in the real
         *  per-candidate flag afterward (review fix minor 1). */
        TargetCandidate toTarget() {
            String name = notBlank(entry.firstName()) || notBlank(entry.lastName())
                    ? (nullToEmpty(entry.firstName()) + " " + nullToEmpty(entry.lastName())).strip()
                    : entry.displayName();
            return new TargetCandidate(entry.id(), name, score, false);
        }
    }
}
