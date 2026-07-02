import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { ParticipantProfile, RecomputeLevelsResult } from "./types";

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
