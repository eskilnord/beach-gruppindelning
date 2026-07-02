import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { invalidateExplanationQueries } from "./explanations";
import type { AssignmentsView, MoveAssignmentRequest, PlayerAssignment } from "./types";

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

/** M7 "Flytta ändå"/"Lås & markera för omoptimering" (spec §18.2/§18.3, what-if dialog action
 *  buttons): the actual MUTATING manual move behind the what-if consequence report, `source=manual`
 *  on success (AssignmentController#move). `groupId: null` moves the participant to the kölista.
 *  Bumps `plan_revision`, so every open explanation for this plan flips `stale=true` until the next
 *  solve - invalidated here alongside assignments/groups. */
export function useMoveAssignment(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ participantProfileId, groupId }: { participantProfileId: string; groupId: string | null }) =>
      api.post<PlayerAssignment>(`/api/plans/${planId}/assignments/${participantProfileId}/move`, {
        groupId,
      } satisfies MoveAssignmentRequest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
      invalidateExplanationQueries(queryClient, planId);
    },
  });
}
