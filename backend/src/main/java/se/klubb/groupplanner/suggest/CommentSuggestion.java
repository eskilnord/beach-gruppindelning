package se.klubb.groupplanner.suggest;

import java.util.List;

/**
 * One rule-based interpretation proposed from a participant's free-text {@code imported_comment}
 * (WP2). Non-binding: the council always confirms via "Lägg till" before anything is written to a
 * structured field — see {@link CommentSuggestionService}'s class javadoc for the full privacy
 * contract this record's fields must respect.
 *
 * @param fingerprint stable id derived from {@code participantId|kind|cueOffset|sortedTargetIds} —
 *     used by the frontend's session-local "Avfärda" (dismiss) set; recomputed on every request,
 *     never persisted.
 * @param kind which rule fired.
 * @param matchedText the comment span the rule matched on (cue + name/time window) — same privacy
 *     status as {@code GET .../participants/{id}}'s {@code importedComment}, since it is always a
 *     verbatim substring of that same field.
 * @param fieldKey the {@code custom_field_value} field key to write on apply, or {@code null} for
 *     the two flag kinds ({@link SuggestionKind#isFlagKind()}).
 * @param targets up to 3 candidate roster matches, best first; empty for time/flag kinds.
 * @param timeSlotIds every plan {@code TimeSlot} id the weekday/time cue resolved to; empty unless
 *     {@link SuggestionKind#targetsTimeSlots()}.
 * @param confidence {@link Confidence#HIGH} for an unambiguous match, {@link Confidence#UNCERTAIN}
 *     when 2-3 roster candidates are all plausible (the frontend then asks the user to pick one).
 * @param alreadyApplied review fix (minor 1): {@code true} only when the suggestion is fully resolved
 *     already — a single-candidate ({@code targets.size() == 1}) suggestion whose one target is
 *     already applied, an ALL-candidates-applied UNCERTAIN suggestion, or (time/flag kinds) the usual
 *     "everything this suggestion names is already present" check. A single unrelated pre-existing
 *     entry in the field's current array must never block applying a DIFFERENT UNCERTAIN candidate —
 *     see {@link TargetCandidate#applied()} for the per-candidate flag the frontend actually uses to
 *     grey out already-applied options in the picker.
 */
public record CommentSuggestion(
        String fingerprint,
        SuggestionKind kind,
        String matchedText,
        String fieldKey,
        List<TargetCandidate> targets,
        List<String> timeSlotIds,
        Confidence confidence,
        boolean alreadyApplied) {

    /** One roster candidate a name in the comment might refer to. {@code applied} (review fix minor 1)
     *  is {@code true} when THIS specific candidate's id is already present in the participant's
     *  current field value — independent of whether some OTHER candidate is also present. */
    public record TargetCandidate(String id, String displayName, double score, boolean applied) {
    }

    public enum Confidence {
        HIGH, UNCERTAIN
    }

    /** Every suggestion (possibly empty) found for one participant's comment. */
    public record ParticipantSuggestions(String participantId, List<CommentSuggestion> suggestions) {
    }
}
