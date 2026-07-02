import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { AssignmentsView } from "./types";

export const assignmentsKey = (planId: string) => ["plans", planId, "assignments"] as const;

/** Every player/coach assignment for a plan (docs/design/04-solver.md §5, spec §19.10 Resultatvy) -
 *  `group_id == null` on a player row means unassigned/waitlisted (the OPLACERAD/KÖLISTA bucket). */
export function useAssignments(planId: string | undefined) {
  return useQuery({
    queryKey: assignmentsKey(planId ?? ""),
    queryFn: () => api.get<AssignmentsView>(`/api/plans/${planId}/assignments`),
    enabled: planId !== undefined,
  });
}

/** §15.1 "Lås spelare" (spec §15.1): pins a participant to a specific group. */
export function useLockPlayerAssignment(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ participantProfileId, groupId }: { participantProfileId: string; groupId: string }) =>
      api.put<void>(`/api/plans/${planId}/assignments/${participantProfileId}/lock`, { groupId }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
    },
  });
}

export function useUnlockPlayerAssignment(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ participantProfileId }: { participantProfileId: string }) =>
      api.delete<void>(`/api/plans/${planId}/assignments/${participantProfileId}/lock`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
    },
  });
}
