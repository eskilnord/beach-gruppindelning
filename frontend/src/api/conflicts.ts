import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { SeasonConflict } from "./types";

export const seasonConflictsKey = (seasonId: string) => ["seasons", seasonId, "conflicts"] as const;

/** Season-wide double-booking sweep (spec §19.2/§14.4, docs/design/04-solver.md §14.2) - person/
 *  coach/court×time overlaps across every plan in the season, regardless of status. Surfaced on the
 *  Planeringskarta (spec §19.11: "Konflikter ska markeras tydligt"). */
export function useSeasonConflicts(seasonId: string | undefined) {
  return useQuery({
    queryKey: seasonConflictsKey(seasonId ?? ""),
    queryFn: () => api.get<SeasonConflict[]>(`/api/seasons/${seasonId}/conflicts`),
    enabled: seasonId !== undefined,
  });
}
