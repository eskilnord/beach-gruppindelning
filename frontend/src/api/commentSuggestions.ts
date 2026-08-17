import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { ParticipantCommentSuggestions, ParticipantSuggestionCount } from "./types";

/** Query key for one participant's "Tolkningsförslag" (WP2) - exported so `fieldValues.ts`/
 *  `participants.ts` can invalidate it after a mutation that could change `alreadyApplied` (a new
 *  field value or `manualReviewFlag` PATCH) without importing this whole hook module back. */
export const commentSuggestionsKey = (planId: string, participantId: string) =>
  ["plans", planId, "participants", participantId, "comment-suggestions"] as const;

/** Query key for the plan-wide "Tolkningsförslag" list (backs the Deltagarvy grid's per-row badge). */
export const planCommentSuggestionsKey = (planId: string) => ["plans", planId, "comment-suggestions"] as const;

/** One participant's rule-based comment suggestions (WP2) - rendered under the imported comment in
 *  {@link ../routes/plan/participants/ParticipantDrawer.tsx}. Computed on demand server-side, never
 *  cached in the DB (see backend `CommentSuggestionService`'s class javadoc) - this query's own
 *  cache is the only caching involved, same as any other read. */
export function useCommentSuggestions(planId: string | undefined, participantId: string | undefined) {
  return useQuery({
    queryKey: commentSuggestionsKey(planId ?? "", participantId ?? ""),
    queryFn: () => api.get<ParticipantCommentSuggestions>(`/api/plans/${planId}/participants/${participantId}/comment-suggestions`),
    enabled: planId !== undefined && participantId !== undefined,
  });
}

/** Every participant in the plan with at least one unresolved comment suggestion - backs the
 *  Deltagarvy grid's per-row suggestion count badge ({@code ParticipantsPanel.tsx}'s `CommentCell`).
 *  Review fix (MAJOR 6, "comment minimization"): counts only, never `matchedText`/candidate names
 *  for the whole roster - see `ParticipantSuggestionCount`'s javadoc. */
export function usePlanCommentSuggestions(planId: string | undefined) {
  return useQuery({
    queryKey: planCommentSuggestionsKey(planId ?? ""),
    queryFn: () => api.get<ParticipantSuggestionCount[]>(`/api/plans/${planId}/comment-suggestions`),
    enabled: planId !== undefined,
  });
}
