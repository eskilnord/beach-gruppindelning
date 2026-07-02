import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { FieldValueView } from "./types";

const fieldValuesKey = (planId: string, participantId: string) =>
  ["plans", planId, "participants", participantId, "field-values"] as const;

/** A participant's CUSTOM-storage field values (spec §7.14) - every field defined on the plan gets
 *  one entry, `value: null` when nothing has been entered yet (M4 Deltagarvy drawer). */
export function useParticipantFieldValues(planId: string | undefined, participantId: string | undefined) {
  return useQuery({
    queryKey: fieldValuesKey(planId ?? "", participantId ?? ""),
    queryFn: () => api.get<FieldValueView[]>(`/api/plans/${planId}/participants/${participantId}/field-values`),
    enabled: planId !== undefined && participantId !== undefined,
  });
}

/** Bulk-writes a subset of a participant's CUSTOM-storage field values, keyed by field `key`. A
 *  JSON `null` value explicitly clears that field (same absent-vs-null convention as the
 *  participant PATCH endpoint, but every key present in this map is by definition "present" -
 *  omit a key entirely to leave it untouched). */
export function useUpdateParticipantFieldValues(planId: string, participantId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      api.put<FieldValueView[]>(`/api/plans/${planId}/participants/${participantId}/field-values`, values),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: fieldValuesKey(planId, participantId) });
    },
  });
}
