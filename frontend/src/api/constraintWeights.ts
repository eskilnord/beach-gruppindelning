import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { priorityOrderKey } from "./priorityOrder";
import type { ConstraintWeightOverrideRequest, ConstraintWeightView } from "./types";

// Exported (v0.6.0 F3) so PrioritiesPanel.tsx can invalidate this cache entry after the "Återställ
// till prioriteringsordning" reset flow, whose PUT /priority-order restores the plan's bucket
// constraint weights server-side but doesn't itself touch this query's cache.
export const constraintWeightsKey = (planId: string) => ["plans", planId, "constraint-weights"] as const;

/** Per-plan constraint weight overrides (spec §9.4/§7.16) - the 24 standard constraints merged with
 *  this plan's overrides, if any ("Konfiguration" section of Fältbyggaren, M4). */
export function useConstraintWeights(planId: string | undefined) {
  return useQuery({
    queryKey: constraintWeightsKey(planId ?? ""),
    queryFn: () => api.get<ConstraintWeightView[]>(`/api/plans/${planId}/constraint-weights`),
    enabled: planId !== undefined,
  });
}

/** Applies one or more partial overrides in a single request. Guardrail errors (e.g. trying to
 *  disable/reclassify a MEDIUM-reserved system constraint, or weight < 1) surface as ApiError from
 *  the backend and are shown as toasts by the caller.
 *
 *  v0.6.0 F3 review fix (FIX 8, MINOR, symmetric cache invalidation): also invalidates
 *  `priorityOrderKey` - an advanced-mode weight edit can move the plan into (or out of)
 *  `customWeightsActive` and change the backend's best-effort inferred `order`
 *  (api/priorityOrder.ts's PriorityOrderView doc comment), so a mounted PrioritiesPanel must refetch
 *  rather than keep showing a now-stale view. Mirrors useSetPriorityOrder's reverse-direction
 *  invalidation of `constraintWeightsKey` in api/priorityOrder.ts. */
export function useUpdateConstraintWeights(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (requests: ConstraintWeightOverrideRequest[]) =>
      api.put<ConstraintWeightView[]>(`/api/plans/${planId}/constraint-weights`, requests),
    onSuccess: (data) => {
      queryClient.setQueryData(constraintWeightsKey(planId), data);
      void queryClient.invalidateQueries({ queryKey: priorityOrderKey(planId) });
    },
  });
}
