import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { ConstraintWeightOverrideRequest, ConstraintWeightView } from "./types";

const constraintWeightsKey = (planId: string) => ["plans", planId, "constraint-weights"] as const;

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
 *  the backend and are shown as toasts by the caller. */
export function useUpdateConstraintWeights(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (requests: ConstraintWeightOverrideRequest[]) =>
      api.put<ConstraintWeightView[]>(`/api/plans/${planId}/constraint-weights`, requests),
    onSuccess: (data) => {
      queryClient.setQueryData(constraintWeightsKey(planId), data);
    },
  });
}
