import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { CreateSavedPlanRequest, SavedPlan, SavedPlanDetailView } from "./types";

export const savedPlansKey = (planId: string) => ["plans", planId, "saved-plans"] as const;

/** Sparade planer for an activity plan (spec §14.1) - every `POST` creates a brand-new immutable
 *  snapshot row rather than updating one in place, so this list is the plan's full save history,
 *  oldest first (backend: `SavedPlanRepository.findByActivityPlanId`, `ORDER BY created_at, id`). */
export function useSavedPlans(planId: string | undefined) {
  return useQuery({
    queryKey: savedPlansKey(planId ?? ""),
    queryFn: () => api.get<SavedPlan[]>(`/api/plans/${planId}/saved-plans`),
    enabled: planId !== undefined,
  });
}

/** "Spara plan" (spec §14.1) - snapshots groups/spelare/tränare/tid/bana/constraint-vikter/score as
 *  they stand right now. Always lands in status "saved" (the backend never produces a "draft" row via
 *  this endpoint - see SavedPlanService#save). */
export function useCreateSavedPlan(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSavedPlanRequest) =>
      api.post<SavedPlanDetailView>(`/api/plans/${planId}/saved-plans`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedPlansKey(planId) });
    },
  });
}

/** Status transition (spec §14.2/§14.3): draft->saved->locked->published->archived. 409s on an
 *  illegal transition (see `savedPlanActions.ts` for the legal-transition table this UI renders
 *  buttons from, so illegal requests are never actually sent in normal use). Locking materializes
 *  `saved_plan_resource_usage`, which the season Konflikter panel and cross-plan solve blocking
 *  (§14.4) both read. */
export function useUpdateSavedPlanStatus(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ savedPlanId, status }: { savedPlanId: string; status: string }) =>
      api.patch<SavedPlanDetailView>(`/api/plans/${planId}/saved-plans/${savedPlanId}`, { status }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedPlansKey(planId) });
    },
  });
}

/** DELETE - only legal while status is still draft/saved (409 otherwise, see
 *  `savedPlanActions.ts#canDeleteSavedPlan`); locked/published/archived plans must be archived
 *  instead, never deleted. */
export function useDeleteSavedPlan(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (savedPlanId: string) => api.delete<void>(`/api/plans/${planId}/saved-plans/${savedPlanId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedPlansKey(planId) });
    },
  });
}
