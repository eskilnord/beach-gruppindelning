import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { FieldValueView } from "./types";

const coachFieldValuesKey = (planId: string, coachId: string) =>
  ["plans", planId, "coaches", coachId, "field-values"] as const;

/** A coach's CUSTOM-storage field values (spec §7.14) - the coach-scoped counterpart of
 *  fieldValues.ts's participant hooks (M5, CoachFieldValueController). */
export function useCoachFieldValues(planId: string | undefined, coachId: string | undefined) {
  return useQuery({
    queryKey: coachFieldValuesKey(planId ?? "", coachId ?? ""),
    queryFn: () => api.get<FieldValueView[]>(`/api/plans/${planId}/coaches/${coachId}/field-values`),
    enabled: planId !== undefined && coachId !== undefined,
  });
}

export function useUpdateCoachFieldValues(planId: string, coachId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.put<FieldValueView[]>(`/api/plans/${planId}/coaches/${coachId}/field-values`, values),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: coachFieldValuesKey(planId, coachId) });
    },
  });
}
