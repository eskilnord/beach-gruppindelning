import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { GroupExplanationResponse, ImprovementSuggestionsResponse, PersonExplanationResponse, PlanExplanationResponse } from "./types";

/** Broad prefix key for every explanation query under a plan (person/group/plan level, any run) -
 *  used to invalidate ALL of them in one call after a mutation that bumps `activity_plan
 *  .plan_revision` (manual move, lock/unlock, a new solve...), so any currently-mounted/cached
 *  explain drawer refetches and picks up the new `stale`/content (docs/design/04-solver.md §11.6's
 *  staleness envelope, backend/docs/m7-notes.md). Person/group/plan level explanation content is
 *  ALWAYS re-derived from the CURRENT persisted plan state server-side (never from a historical
 *  snapshot) - only the `stale` flag and `currentRevision` change when nothing else about the
 *  requested run/participant/group did, so invalidating (not removing) is enough: TanStack Query
 *  refetches in the background and callers just see the response's own `stale` flag flip. */
export const explanationsKey = (planId: string) => ["plans", planId, "explanations"] as const;

const personExplanationKey = (planId: string, runId: string, participantProfileId: string) =>
  [...explanationsKey(planId), "runs", runId, "players", participantProfileId] as const;

const groupExplanationKey = (planId: string, runId: string, groupId: string) =>
  [...explanationsKey(planId), "runs", runId, "groups", groupId] as const;

const planExplanationKey = (planId: string, runId: string) =>
  [...explanationsKey(planId), "runs", runId, "plan"] as const;

const suggestionsKey = (planId: string, runId: string) =>
  [...explanationsKey(planId), "runs", runId, "suggestions"] as const;

/** Person-level explanation (spec §17.1/§17.2, "Personnivå" of the Förklarbarhet chapter) - the
 *  explain-drawer's data source for both a placed member (selectedGroup + factors + alternatives)
 *  and a waitlisted one (waitlist narrative instead, selectedGroup omitted). `enabled` requires a
 *  concrete `runId`: there is nothing to explain against before the plan has ever been solved once. */
export function usePersonExplanation(planId: string | undefined, runId: string | undefined, participantProfileId: string | undefined) {
  return useQuery({
    queryKey: personExplanationKey(planId ?? "", runId ?? "", participantProfileId ?? ""),
    queryFn: () =>
      api.get<PersonExplanationResponse>(
        `/api/plans/${planId}/runs/${runId}/explanations/players/${participantProfileId}`,
      ),
    enabled: planId !== undefined && runId !== undefined && participantProfileId !== undefined,
  });
}

/** Group-level explanation (spec §17.1, "Gruppnivå") - matches/warnings/membersWithBrokenWishes for
 *  the "Förklara grupp" action on a GroupCard header. */
export function useGroupExplanation(planId: string | undefined, runId: string | undefined, groupId: string | undefined) {
  return useQuery({
    queryKey: groupExplanationKey(planId ?? "", runId ?? "", groupId ?? ""),
    queryFn: () => api.get<GroupExplanationResponse>(`/api/plans/${planId}/runs/${runId}/explanations/groups/${groupId}`),
    enabled: planId !== undefined && runId !== undefined && groupId !== undefined,
  });
}

/** Plan-level explanation (spec §17.1, "Planeringsnivå") - the Optimeringsvy's "Analys" section:
 *  constraintSummaries/hardViolations/waitlist/problematicGroups/manualReview for the latest run. */
export function usePlanExplanation(planId: string | undefined, runId: string | undefined) {
  return useQuery({
    queryKey: planExplanationKey(planId ?? "", runId ?? ""),
    queryFn: () => api.get<PlanExplanationResponse>(`/api/plans/${planId}/runs/${runId}/explanations/plan`),
    enabled: planId !== undefined && runId !== undefined,
  });
}

/** Improvement suggestions (WI-D "Förbättringsförslag", user feedback v0.4 #2) - the Resultat tab's
 *  post-solve "which small data change would unlock a big improvement" card. Same staleness envelope
 *  and `enabled` gating (no run yet = nothing to suggest against) as the three explanation levels
 *  above; lives under the same {@link explanationsKey} prefix so a plan-mutating action's broad
 *  invalidation picks this up too. */
export function useImprovementSuggestions(planId: string | undefined, runId: string | undefined) {
  return useQuery({
    queryKey: suggestionsKey(planId ?? "", runId ?? ""),
    queryFn: () => api.get<ImprovementSuggestionsResponse>(`/api/plans/${planId}/runs/${runId}/suggestions`),
    enabled: planId !== undefined && runId !== undefined,
  });
}

/** Invalidates every cached explanation for a plan - see {@link explanationsKey}'s javadoc for why a
 *  broad prefix invalidation (rather than a targeted one) is the right granularity here. */
export function invalidateExplanationQueries(queryClient: ReturnType<typeof useQueryClient>, planId: string): void {
  void queryClient.invalidateQueries({ queryKey: explanationsKey(planId) });
}
