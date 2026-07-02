import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { FieldValueView, ParticipantProfile, RecomputeLevelsResult } from "./types";

export const participantsKey = (planId: string) => ["plans", planId, "participants"] as const;

/** Participant profiles for a plan (spec §7.2) - backs the Deltagarvy AG Grid (M4). */
export function useParticipants(planId: string | undefined) {
  return useQuery({
    queryKey: participantsKey(planId ?? ""),
    queryFn: () => api.get<ParticipantProfile[]>(`/api/plans/${planId}/participants`),
    enabled: planId !== undefined,
  });
}

/**
 * Edits a participant's structured (COLUMN-storage) fields (spec §19.4 "Användaren ska kunna
 * redigera strukturerade fält"). `body` is a raw record, not a typed request type, to preserve the
 * backend's absent-vs-null PATCH semantics (docs: ParticipantProfileController) - omit a key to
 * leave it unchanged, send an explicit `null` to clear a nullable column.
 */
export function useUpdateParticipant(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Record<string, unknown> }) =>
      api.patch<ParticipantProfile>(`/api/participants/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: participantsKey(planId) });
    },
  });
}

/** Plan-wide estimatedLevel/levelConfidence recompute (spec §11.2) - the Deltagarvy toolbar's
 *  "Räkna om nivåer" action. */
export function useRecomputeLevels(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<RecomputeLevelsResult>(`/api/plans/${planId}/participants/recompute-levels`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: participantsKey(planId) });
    },
  });
}

/**
 * Fans out one field-values fetch per participant (same `useQueries` pattern as coaches.ts'
 * `useCoachAvailabilitySummaries`) - used by the Resultatvy's OPLACERAD/KÖLISTA card (spec §19.10)
 * to resolve each waitlisted player's "Prioritet" custom field value, if the plan has one configured
 * (constraintType PRIORITY - the same field the solver's `unassignedPlayer` waitlist penalty scales
 * by, per backend/docs/m6b-notes.md). Bounded fan-out is fine here: only waitlisted participants
 * (usually a handful) are ever passed in, never the whole roster.
 */
export function useParticipantFieldValues(
  planId: string | undefined,
  participantIds: string[],
): Record<string, FieldValueView[]> {
  const results = useQueries({
    queries: participantIds.map((id) => ({
      queryKey: ["plans", planId ?? "", "participants", id, "field-values"] as const,
      queryFn: () => api.get<FieldValueView[]>(`/api/plans/${planId}/participants/${id}/field-values`),
      enabled: planId !== undefined,
    })),
  });

  const byParticipantId: Record<string, FieldValueView[]> = {};
  participantIds.forEach((id, index) => {
    byParticipantId[id] = results[index]?.data ?? [];
  });
  return byParticipantId;
}
