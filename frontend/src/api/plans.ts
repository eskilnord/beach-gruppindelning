import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { coachesKey } from "./coaches";
import { groupSyncStatusKey, groupsKey } from "./groups";
import { participantsKey } from "./participants";
import type {
  ActivityPlan,
  CoachProfile,
  CreateActivityPlanRequest,
  ParticipantProfile,
  TrainingGroup,
  UpdateActivityPlanRequest,
} from "./types";

const plansForSeasonKey = (seasonId: string) => ["seasons", seasonId, "plans"] as const;
const planKey = (id: string) => ["plans", id] as const;

export function usePlansForSeason(seasonId: string | undefined) {
  return useQuery({
    queryKey: plansForSeasonKey(seasonId ?? ""),
    queryFn: () => api.get<ActivityPlan[]>(`/api/seasons/${seasonId}/plans`),
    enabled: seasonId !== undefined,
  });
}

export function usePlan(id: string | undefined) {
  return useQuery({
    queryKey: planKey(id ?? ""),
    queryFn: () => api.get<ActivityPlan>(`/api/plans/${id}`),
    enabled: id !== undefined,
  });
}

export function useCreatePlan(seasonId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateActivityPlanRequest) =>
      api.post<ActivityPlan>(`/api/seasons/${seasonId}/plans`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: plansForSeasonKey(seasonId) });
    },
  });
}

export function useUpdatePlan(id: string, seasonId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateActivityPlanRequest) => api.patch<ActivityPlan>(`/api/plans/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: planKey(id) });
      void queryClient.invalidateQueries({ queryKey: plansForSeasonKey(seasonId) });
      // Bugfix (WI-C, "re-run doesn't feel like it re-runs" user feedback v0.4 #4): editing the
      // plan's group-generation defaults (target/min/max/min-nivå) used to invalidate NEITHER the
      // groups list NOR the new sync-status check, so OptimizePanel's "Grupper"/staleness banner
      // could sit stale on screen indefinitely after a plan-defaults edit.
      void queryClient.invalidateQueries({ queryKey: groupsKey(id) });
      void queryClient.invalidateQueries({ queryKey: groupSyncStatusKey(id) });
    },
  });
}

/**
 * "Senaste planer" on the Startvy needs activity plans across ALL seasons, but M1's REST surface
 * only exposes per-season listing (docs/design/02-product-data-ui.md §8 correction note: no global
 * plans endpoint). Fan out one query per season id and merge client-side rather than adding a new
 * backend endpoint (out of scope for M2 — see milestone brief).
 */
export function useRecentPlans(seasonIds: string[], limit = 5) {
  const results = useQueries({
    queries: seasonIds.map((seasonId) => ({
      queryKey: plansForSeasonKey(seasonId),
      queryFn: () => api.get<ActivityPlan[]>(`/api/seasons/${seasonId}/plans`),
    })),
  });

  const isLoading = results.some((result) => result.isLoading);
  const plans = results.flatMap((result) => result.data ?? []);
  const recent = [...plans]
    .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""))
    .slice(0, limit);

  return { plans: recent, isLoading };
}

export interface PlanCounts {
  participants: number;
  groups: number;
  coaches: number;
}

/**
 * Deltagare/Grupper/Tränare counts per plan for the Säsongsvy table (spec §19.2). `ActivityPlan`
 * carries no count fields and the M8 backend exposes no plan-summary/resource-usage aggregate
 * endpoint (SavedPlanResourceUsage is internal-only, consumed only by ConflictService/the solver's
 * cross-plan blocking assembler) - adding one is out of scope for this milestone's frontend-only
 * half, so this fans out the existing per-plan list endpoints instead, one `useQueries` batch per
 * resource type (same N+1 fan-out shape as `useRecentPlans` above). Reuses the exact query keys
 * `useParticipants`/`useGroups`/`useCoaches` already use, so navigating from Säsongsvy into a plan's
 * own tabs hits a warm cache instead of refetching.
 */
export function usePlanCounts(planIds: string[]): { counts: Record<string, PlanCounts>; isLoading: boolean } {
  const participantResults = useQueries({
    queries: planIds.map((planId) => ({
      queryKey: participantsKey(planId),
      queryFn: () => api.get<ParticipantProfile[]>(`/api/plans/${planId}/participants`),
    })),
  });
  const groupResults = useQueries({
    queries: planIds.map((planId) => ({
      queryKey: groupsKey(planId),
      queryFn: () => api.get<TrainingGroup[]>(`/api/plans/${planId}/groups`),
    })),
  });
  const coachResults = useQueries({
    queries: planIds.map((planId) => ({
      queryKey: coachesKey(planId),
      queryFn: () => api.get<CoachProfile[]>(`/api/plans/${planId}/coaches`),
    })),
  });

  const isLoading = [...participantResults, ...groupResults, ...coachResults].some((result) => result.isLoading);

  const counts: Record<string, PlanCounts> = {};
  planIds.forEach((planId, index) => {
    counts[planId] = {
      participants: participantResults[index].data?.length ?? 0,
      groups: groupResults[index].data?.length ?? 0,
      coaches: coachResults[index].data?.length ?? 0,
    };
  });

  return { counts, isLoading };
}

export function useDeletePlan(seasonId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/plans/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: plansForSeasonKey(seasonId) });
    },
  });
}
