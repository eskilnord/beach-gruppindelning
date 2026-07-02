import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { TrainingGroup } from "./types";
import { assignmentsKey } from "./assignments";

export const groupsKey = (planId: string) => ["plans", planId, "groups"] as const;

/** A plan's generated groups (spec §7.4/§19.10) - created via {@link useGenerateGroups}, then
 *  assigned tid/bana/tränare/spelare by a solve or by manual locks. */
export function useGroups(planId: string | undefined) {
  return useQuery({
    queryKey: groupsKey(planId ?? ""),
    queryFn: () => api.get<TrainingGroup[]>(`/api/plans/${planId}/groups`),
    enabled: planId !== undefined,
  });
}

/** (Re)generates groups per docs/design/04-solver.md §7's policy (count = clamp(ceil(active/target),
 *  1, activeBlocks)) - the prerequisite step before a solve has anything to assign players into.
 *  409s if any existing group/assignment is already locked (surfaced as an ApiError by the caller). */
export function useGenerateGroups(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<TrainingGroup[]>(`/api/plans/${planId}/groups/generate`),
    onSuccess: (data) => {
      queryClient.setQueryData(groupsKey(planId), data);
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
    },
  });
}

/** §15.2 "Lås gruppens tid/bana" (spec §15.2). */
export function useLockGroupBlock(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, trainingBlockId }: { groupId: string; trainingBlockId: string }) =>
      api.put<TrainingGroup>(`/api/groups/${groupId}/lock-block`, { trainingBlockId }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
    },
  });
}

export function useUnlockGroupBlock(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId }: { groupId: string }) => api.delete<void>(`/api/groups/${groupId}/lock-block`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
    },
  });
}

/** §15.3 "Lås tränare" (spec §15.3). */
export function useLockGroupCoach(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, coachProfileId }: { groupId: string; coachProfileId: string }) =>
      api.put<TrainingGroup>(`/api/groups/${groupId}/lock-coach`, { coachProfileId }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
    },
  });
}

export function useUnlockGroupCoach(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, coachProfileId }: { groupId: string; coachProfileId: string }) =>
      api.delete<void>(`/api/groups/${groupId}/lock-coach?coachProfileId=${encodeURIComponent(coachProfileId)}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: groupsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: assignmentsKey(planId) });
    },
  });
}
