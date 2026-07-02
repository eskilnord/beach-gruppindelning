import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { CreateTimeSlotRequest, TimeSlot } from "./types";
import { trainingBlocksKey } from "./trainingBlocks";

export const timeSlotsKey = (planId: string) => ["plans", planId, "time-slots"] as const;

/** A plan's time slots (spec §12.1/§19.6), flat list - id + label is what the Tränarvy availability
 *  matrix (M5) needs; the Resursvy itself reads the richer grouped view (see trainingBlocks.ts). */
export function useTimeSlots(planId: string | undefined) {
  return useQuery({
    queryKey: timeSlotsKey(planId ?? ""),
    queryFn: () => api.get<TimeSlot[]>(`/api/plans/${planId}/time-slots`),
    enabled: planId !== undefined,
  });
}

/** Creates a time slot (Resursvy "Ny tid" modal, spec §12.1). No courts/blocks are created here -
 *  that's a separate "Antal banor" step (see trainingBlocks.ts useSetCourts). */
export function useCreateTimeSlot(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateTimeSlotRequest) => api.post<TimeSlot>(`/api/plans/${planId}/time-slots`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: timeSlotsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: trainingBlocksKey(planId) });
    },
  });
}

/** Edits a time slot's day/time/label (raw partial body - same absent-vs-null PATCH convention as
 *  the participant/coach controllers). */
export function useUpdateTimeSlot(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Record<string, unknown> }) =>
      api.patch<TimeSlot>(`/api/time-slots/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: timeSlotsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: trainingBlocksKey(planId) });
    },
  });
}

export function useDeleteTimeSlot(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/time-slots/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: timeSlotsKey(planId) });
      void queryClient.invalidateQueries({ queryKey: trainingBlocksKey(planId) });
    },
  });
}
