import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { CreateSeasonPlanRequest, SeasonPlan, UpdateSeasonPlanRequest } from "./types";

const seasonsKey = ["seasons"] as const;
const seasonKey = (id: string) => ["seasons", id] as const;

export function useSeasons() {
  return useQuery({
    queryKey: seasonsKey,
    queryFn: () => api.get<SeasonPlan[]>("/api/seasons"),
  });
}

export function useSeason(id: string | undefined) {
  return useQuery({
    queryKey: seasonKey(id ?? ""),
    queryFn: () => api.get<SeasonPlan>(`/api/seasons/${id}`),
    enabled: id !== undefined,
  });
}

export function useCreateSeason() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSeasonPlanRequest) => api.post<SeasonPlan>("/api/seasons", body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: seasonsKey });
    },
  });
}

export function useUpdateSeason(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateSeasonPlanRequest) => api.patch<SeasonPlan>(`/api/seasons/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: seasonsKey });
      void queryClient.invalidateQueries({ queryKey: seasonKey(id) });
    },
  });
}

export function useDeleteSeason() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/seasons/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: seasonsKey });
    },
  });
}
