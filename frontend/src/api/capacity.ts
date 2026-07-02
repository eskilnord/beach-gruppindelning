import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { CapacityResponse } from "./types";

export const capacityKey = (planId: string) => ["plans", planId, "capacity"] as const;

/**
 * Pre-solve capacity analysis (spec §12.4/§19.8) - `GET /api/plans/{planId}/capacity`.
 *
 * `refetchOnMount: "always"` (rather than relying on cross-tab invalidation from every
 * resources/coaches mutation) is the M5 brief's "auto-refetch on tab focus": since the plan layout
 * unmounts/remounts each tab's panel on navigation (react-router, not a kept-alive tab), landing on
 * Kapacitet always re-fetches fresh numbers regardless of what changed on Resurser/Tränare in the
 * meantime. `refetchOnWindowFocus` additionally covers the OS-level window-refocus case.
 */
export function useCapacity(planId: string | undefined) {
  return useQuery({
    queryKey: capacityKey(planId ?? ""),
    queryFn: () => api.get<CapacityResponse>(`/api/plans/${planId}/capacity`),
    enabled: planId !== undefined,
    refetchOnMount: "always",
    refetchOnWindowFocus: true,
  });
}
