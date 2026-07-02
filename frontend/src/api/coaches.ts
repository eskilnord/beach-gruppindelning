import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { AvailabilityEntry, CoachProfile, CreateCoachRequest } from "./types";
import { capacityKey } from "./capacity";

export const coachesKey = (planId: string) => ["plans", planId, "coaches"] as const;
export const coachAvailabilityKey = (planId: string, coachId: string) =>
  ["plans", planId, "coaches", coachId, "availability"] as const;

/** A plan's coach register (spec §13.1/§19.7). */
export function useCoaches(planId: string | undefined) {
  return useQuery({
    queryKey: coachesKey(planId ?? ""),
    queryFn: () => api.get<CoachProfile[]>(`/api/plans/${planId}/coaches`),
    enabled: planId !== undefined,
  });
}

/** "Ny tränare" modal (spec §13.1): links an existing Person (personId) or creates a new one inline
 *  (firstName+lastName) - see CoachController.resolvePersonId for which branch the backend takes. */
export function useCreateCoach(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCoachRequest) => api.post<CoachProfile>(`/api/plans/${planId}/coaches`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: coachesKey(planId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}

/** Edits a coach's profile fields (level/max groups/also-plays/notes) - raw partial body, same
 *  absent-vs-null PATCH convention as ParticipantProfile. */
export function useUpdateCoach(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Record<string, unknown> }) =>
      api.patch<CoachProfile>(`/api/coaches/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: coachesKey(planId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}

export function useDeleteCoach(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/coaches/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: coachesKey(planId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}

/** A coach's tri-state availability (spec §13.1) - a time slot with no entry is neutral/unknown, not
 *  UNAVAILABLE (see CoachTimeSlot javadoc). */
export function useCoachAvailability(planId: string | undefined, coachId: string | undefined) {
  return useQuery({
    queryKey: coachAvailabilityKey(planId ?? "", coachId ?? ""),
    queryFn: () => api.get<AvailabilityEntry[]>(`/api/plans/${planId}/coaches/${coachId}/availability`),
    enabled: planId !== undefined && coachId !== undefined,
  });
}

/** Full-replace PUT (spec §13.1 Tränarvy availability matrix): entries omitted from the array revert
 *  to neutral/unknown. Availability also feeds the Kapacitetsvy's per-slot coach counts and
 *  tränarbrist estimate, so this invalidates capacity too. */
export function useSetCoachAvailability(planId: string, coachId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (entries: AvailabilityEntry[]) =>
      api.put<AvailabilityEntry[]>(`/api/plans/${planId}/coaches/${coachId}/availability`, entries),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: coachAvailabilityKey(planId, coachId) });
      void queryClient.invalidateQueries({ queryKey: capacityKey(planId) });
    },
  });
}

export interface CoachAvailabilitySummary {
  available: number;
  unavailable: number;
  preferred: number;
}

const EMPTY_SUMMARY: CoachAvailabilitySummary = { available: 0, unavailable: 0, preferred: 0 };

function summarize(entries: AvailabilityEntry[] | undefined): CoachAvailabilitySummary {
  if (!entries) {
    return EMPTY_SUMMARY;
  }
  const summary = { ...EMPTY_SUMMARY };
  for (const entry of entries) {
    if (entry.kind === "AVAILABLE") summary.available += 1;
    else if (entry.kind === "UNAVAILABLE") summary.unavailable += 1;
    else if (entry.kind === "PREFERRED") summary.preferred += 1;
  }
  return summary;
}

/** Fans out one availability fetch per coach (same useQueries pattern as plans.ts useRecentPlans) to
 *  back the Tränarvy table's "availability summary chips" column (spec §19.7) - the list endpoint
 *  itself returns bare CoachProfile rows with no availability info. */
export function useCoachAvailabilitySummaries(
  planId: string | undefined,
  coachIds: string[],
): Record<string, CoachAvailabilitySummary> {
  const results = useQueries({
    queries: coachIds.map((coachId) => ({
      queryKey: coachAvailabilityKey(planId ?? "", coachId),
      queryFn: () => api.get<AvailabilityEntry[]>(`/api/plans/${planId}/coaches/${coachId}/availability`),
      enabled: planId !== undefined,
    })),
  });

  const summaries: Record<string, CoachAvailabilitySummary> = {};
  coachIds.forEach((coachId, index) => {
    summaries[coachId] = summarize(results[index]?.data);
  });
  return summaries;
}
