import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { AnonymizeResult } from "./types";
import { participantsKey } from "./participants";

/** Clears one participant's comments (spec §21.2 "ska kunna raderas") - the per-participant
 *  "Radera kommentarer" action in the Deltagarvy drawer. Irreversible. */
export function useDeleteParticipantComments(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (participantId: string) =>
      api.delete<void>(`/api/plans/${planId}/participants/${participantId}/comments`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: participantsKey(planId) });
    },
  });
}

/** Clears comments for every participant in the plan (spec §21.2 "ska kunna anonymiseras") - the
 *  plan-wide "Anonymisera kommentarer" toolbar action. Requires an explicit confirm:true body
 *  (backend rejects otherwise) - irreversible. */
export function useAnonymizeAllComments(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<AnonymizeResult>(`/api/plans/${planId}/comments/anonymize`, { confirm: true }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: participantsKey(planId) });
    },
  });
}
