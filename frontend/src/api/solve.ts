import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { Query } from "@tanstack/react-query";
import { api } from "./client";
import type {
  CancelSolveResponse,
  LiveSnapshot,
  SolveRequestBody,
  SolveStatus,
  StartSolveResponse,
  SuggestDurationResponse,
} from "./types";
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

export const liveSolutionKey = (planId: string) => ["plans", planId, "solve", "live"] as const;

/**
 * v0.3.0 WI-2 ("se det live" — user feedback: watching groups form/reshuffle live while a solve
 * runs is "en nice marknadsföringsgrej"). Polls `GET .../solve/live` every 500ms while `enabled` -
 * the caller (OptimizePanel/LiveSolveView) gates that on "a non-GREEDY solve is currently running",
 * so this never polls for the synchronous GREEDY baseline (no live view, spec) or once the solve has
 * settled. `queryFn` normalizes the backend's 204-No-Content ("no snapshot for this plan yet") to
 * `null` - TanStack Query v5 treats a literal `undefined` query result as an error, not "no data".
 *
 * `placeholderData: keepPreviousData` keeps the LAST frame rendered between polls instead of
 * flashing to `null` on every refetch; `LiveSolveView` also needs a stable previous snapshot of its
 * own (tracked separately via a ref) to detect which players moved groups since the last frame it
 * actually rendered.
 */
export function useLiveSolution(planId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: liveSolutionKey(planId ?? ""),
    queryFn: async () => (await api.get<LiveSnapshot | undefined>(`/api/plans/${planId}/solve/live`)) ?? null,
    enabled: planId !== undefined && enabled,
    refetchInterval: enabled ? 500 : false,
    placeholderData: keepPreviousData,
  });
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
      // v0.3.0 WI-2: clear any live-view frame left over from a PREVIOUS run for this plan (including
      // a GREEDY run, which never touches the backend's live registry at all) - `LiveSolveView` is
      // conditionally mounted on "a live snapshot exists", so wiping the cache here unmounts it
      // immediately and lets a fresh mount (once the new run's own first frame arrives) start with no
      // stale moved-player history. Harmless no-op for GREEDY: nothing ever repopulates it.
      void queryClient.resetQueries({ queryKey: liveSolutionKey(planId) });
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
