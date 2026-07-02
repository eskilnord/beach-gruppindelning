import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { ParticipantProfile } from "./types";

const participantsKey = (planId: string) => ["plans", planId, "participants"] as const;

/** Participant profiles for a plan (spec §7.2) — used by the basic Deltagare tab (M4 replaces this
 *  with the full AG Grid Deltagarvy). */
export function useParticipants(planId: string | undefined) {
  return useQuery({
    queryKey: participantsKey(planId ?? ""),
    queryFn: () => api.get<ParticipantProfile[]>(`/api/plans/${planId}/participants`),
    enabled: planId !== undefined,
  });
}
