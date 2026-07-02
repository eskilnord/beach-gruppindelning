import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Query } from "@tanstack/react-query";
import { api } from "./client";
import type { CancelSolveResponse, SolveRequestBody, SolveStatus, StartSolveResponse, SuggestDurationResponse } from "./types";
import { assignmentsKey } from "./assignments";
import { groupsKey } from "./groups";

const SOLVING_STATUSES = new Set(["SOLVING_ACTIVE", "SOLVING_SCHEDULED"]);

export const solveStatusKey = (planId: string) => ["plans", planId, "solve", "status"] as const;

/** Solve status (docs/design/04-solver.md §14.2/§9.4, spec §19.9) - polls every 1s while a solve is
 *  actually running (SOLVING_ACTIVE/SOLVING_SCHEDULED), stops polling once it settles
 *  (NOT_SOLVING/FINISHED), matching the milestone brief's "poll status every 1s while solving". */
export function useSolveStatus(planId: string | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: solveStatusKey(planId ?? ""),
    queryFn: () => api.get<SolveStatus>(`/api/plans/${planId}/solve/status`),
    enabled: planId !== undefined && options.enabled !== false,
    refetchInterval: (query: Query<SolveStatus>) => (SOLVING_STATUSES.has(query.state.data?.status ?? "") ? 1000 : false),
  });
}

export function isSolveRunning(status: string | undefined): boolean {
  return SOLVING_STATUSES.has(status ?? "");
}

export const suggestDurationKey = (planId: string) => ["plans", planId, "solve", "suggest-duration"] as const;

/**
 * v0.2.0 (SUGGESTED OPTIMIZATION TIME): `POST .../solve/suggest-duration` - proposes a CUSTOM
 * `durationSeconds` from the plan's problem size + a cached hardware benchmark. A POST modeled as a
 * useQuery (not a mutation): it's a read-only computation the Optimeringsvy wants eagerly on tab
 * open, with `refetch()` as the manual "Uppdatera förslag" re-run. Callers gate `enabled` on "no
 * solve running" (the endpoint 409s during an active solve - the benchmark competes for CPU); the
 * remaining 409 race window is surfaced to the user as the card's solve-active info state.
 * `retry: false`: the first-ever call runs a multi-second benchmark - auto-retrying a 409/failure
 * on top of that only piles on CPU work; `refetchOnWindowFocus: false` for the same reason.
 */
export function useSuggestDuration(planId: string | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: suggestDurationKey(planId ?? ""),
    queryFn: () => api.post<SuggestDurationResponse>(`/api/plans/${planId}/solve/suggest-duration`),
    enabled: planId !== undefined && options.enabled !== false,
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: 60_000,
  });
}

/** Starts a solve (spec §16.6 profiles FAST/NORMAL/THOROUGH wall-clock, or the synchronous GREEDY
 *  baseline, spec §16.7) - 202/200 with a runId; progress is then read via {@link useSolveStatus}.
 *  409s if a solve is already in progress for this plan. */
export function useStartSolve(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SolveRequestBody) => api.post<StartSolveResponse>(`/api/plans/${planId}/solve`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: solveStatusKey(planId) });
    },
  });
}

/** Cancels an in-progress solve (spec §15.4/§19.9) - the best-so-far result is still persisted. */
export function useCancelSolve(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<CancelSolveResponse>(`/api/plans/${planId}/solve/cancel`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: solveStatusKey(planId) });
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
    },
  });
}

/** Invalidates the Resultatvy-facing queries (assignments/groups) - called once a solve/greedy run
 *  is observed to have finished, so group cards/schedule reflect the just-written result without
 *  waiting for an unrelated navigation/refetch. */
export function invalidateResultQueries(
  queryClient: ReturnType<typeof useQueryClient>,
  planId: string,
): void {
  void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
  void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
}
