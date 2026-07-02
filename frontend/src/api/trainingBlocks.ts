import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { SlotBlocksView, TrainingBlockView } from "./types";
import { capacityKey } from "./capacity";

export const trainingBlocksKey = (planId: string) => ["plans", planId, "training-blocks"] as const;

/** Grouped Resursvy view (spec §19.6/§12.2): every time slot in the plan with its (sorted)
 *  TrainingBlocks, "Bana 1"..."Bana N". */
export function useTrainingBlocksForPlan(planId: string | undefined) {
  return useQuery({
    queryKey: trainingBlocksKey(planId ?? ""),
    queryFn: () => api.get<SlotBlocksView[]>(`/api/plans/${planId}/training-blocks`),
    enabled: planId !== undefined,
  });
}

/** Declares "Antal banor" for one time slot (spec §12.2) - (re)generates its TrainingBlocks,
 *  idempotently. Growing the count adds blocks; shrinking deactivates (never deletes) the
 *  now-out-of-range ones. */
export function useSetCourts(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ slotId, count }: { slotId: string; count: number }) =>
      api.put<TrainingBlockView[]>(`/api/plans/${planId}/time-slots/${slotId}/courts`, { count }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trainingBlocksKey(planId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}

/** Manual exception (spec §12.3): activate/deactivate one TrainingBlock without touching the rest
 *  of its slot - the Resursvy block chip's toggle. */
export function useUpdateTrainingBlockActive(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      api.patch<TrainingBlockView>(`/api/training-blocks/${id}`, { active }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: trainingBlocksKey(planId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}
