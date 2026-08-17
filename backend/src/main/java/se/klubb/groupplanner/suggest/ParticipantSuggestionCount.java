package se.klubb.groupplanner.suggest;

/**
 * Counts-only projection for {@code GET /api/plans/{planId}/comment-suggestions} (review fix MAJOR
 * 6 — "comment minimization"): the plan-wide grid badge only needs a count per participant, never
 * the comment span ({@code matchedText}) or resolved candidate names, so the plan-level endpoint
 * must not ship those for the ENTIRE roster just to render a number. The per-participant endpoint
 * ({@code GET .../participants/{pid}/comment-suggestions}) still returns the full {@link
 * CommentSuggestion} detail — that one is already scoped to a single participant the council has
 * opened the drawer for.
 *
 * @param suggestionCount the number of NOT-YET-applied suggestions (see {@link
 *     CommentSuggestionService}) — a participant with zero unapplied suggestions is omitted from the
 *     response entirely, same as {@code suggestionsForPlan}'s pre-existing "only participants with
 *     ≥1 suggestion" contract.
 */
public record ParticipantSuggestionCount(String participantId, int suggestionCount) {
}
