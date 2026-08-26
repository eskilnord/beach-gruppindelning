import { useQuery } from "@tanstack/react-query";
import { api } from "./client";

/**
 * v0.6.0 F4 (M-S4): whether the plan has constraint-weight priorities customized away from their
 * defaults - drives OptimizePanelSimple's dimmed "Anpassade vikter används - se Prioriteringar"
 * hint. Anticipates a `GET /api/plans/{planId}/priority-order` endpoint from the F3 milestone's real
 * Prioriteringar panel (still a placeholder in this checkout, see PrioritiesPanel.tsx) - hand-written
 * here, not generated, since the endpoint may not be merged yet.
 *
 * Named `PriorityOrderView` (not `...Status`, to match the endpoint's own naming once F3 lands) and
 * kept additive-safe: any extra field F3's real response adds is simply ignored by this shape.
 */
export interface PriorityOrderView {
  customWeightsActive: boolean;
}

export const priorityOrderStatusKey = (planId: string) => ["plans", planId, "priority-order"] as const;

/**
 * v0.6.0 F4 review fix (FIX 2, BLOCKER): the previous doc comment claimed this mirrored
 * `src/components/uimode/UiModeSync.tsx`'s handling of a missing `/api/app-settings` - that's false.
 * UiModeSync has no special 404 handling at all; TanStack Query simply swallows any query error by
 * default (no `throwOnError`), which is why a missing/erroring endpoint there quietly renders nothing
 * rather than a red banner. This hook follows the SAME actual mechanism, deliberately: a 404 (or any
 * other failure) here becomes an errored query, whose `data` stays `undefined` - the caller already
 * only renders its hint when `data?.customWeightsActive` is truthy, so an errored/absent-endpoint
 * query and a genuinely-false response both render nothing, with no bespoke null-coercion needed.
 */
export function usePriorityOrderStatus(planId: string | undefined) {
  return useQuery({
    queryKey: priorityOrderStatusKey(planId ?? ""),
    queryFn: () => api.get<PriorityOrderView>(`/api/plans/${planId}/priority-order`),
    enabled: planId !== undefined,
  });
}
