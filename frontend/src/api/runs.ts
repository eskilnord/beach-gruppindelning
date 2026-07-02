import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { OptimizationRun } from "./types";

export const runsKey = (planId: string) => ["plans", planId, "runs"] as const;

/**
 * Run history (docs/design/02-product-data-ui.md §6 "körhistorik", spec §19.9 "Senaste score"/
 * "Resultatsammanfattning") - most-recent-first (the backend's own ordering). Needed because {@code
 * GET .../solve/status} only carries score data WHILE a solve is actively running - once
 * `SolveCoordinator` persists the final result, its in-memory progress entry is cleared and the
 * status endpoint reverts to an empty `NOT_SOLVING` response (see OptimizationRunController's
 * javadoc). The Optimeringsvy's "last-run summary" is simply `data[0]`.
 */
export function useOptimizationRuns(planId: string | undefined) {
  return useQuery({
    queryKey: runsKey(planId ?? ""),
    queryFn: () => api.get<OptimizationRun[]>(`/api/plans/${planId}/runs`),
    enabled: planId !== undefined,
  });
}
