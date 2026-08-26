import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { constraintWeightsKey } from "./constraintWeights";

/**
 * v0.6.0 F3 (M-S3): `GET/PUT /api/plans/{planId}/priority-order` — the four priority "buckets" the
 * Prioriteringar screen lets an admin rank, each backed by one or more of the 24 standard
 * constraints (see PriorityRowView.constraintKeys). Not in schema.d.ts yet (the endpoint's backend
 * milestone landed without a `npm run typegen` refresh in this checkout — see this milestone's
 * brief) - hand-written here rather than derived from `components["schemas"]`, same precedent as
 * `MoveAssignmentRequest`/`ExportDownload` in api/types.ts.
 */
export type PriorityKey = "TRAIN_TOGETHER" | "PREVIOUS_GROUP" | "PREFERRED_TIME" | "LEVEL";

/** One row of the GET response's `priorities` array - `rank` is this key's 1-based position in
 *  `order` (redundant with the array index, but sent explicitly so a row can be looked up by key
 *  without also carrying `order` around). `labelSv`/`summarySv` are finished Swedish sentences
 *  rendered server-side and MUST be shown verbatim (this milestone's hard rule - no client-side
 *  weight/label wording). `weights` is opaque here (the constraint-key -> ladder-weight map the PUT
 *  produces server-side) - the frontend never does weight math, so it's typed loosely and simply
 *  never read directly by this app. */
export interface PriorityRowView {
  key: PriorityKey;
  rank: number;
  labelSv: string;
  summarySv: string;
  constraintKeys: string[];
  weights: Record<string, number>;
  enabled: boolean;
}

/**
 * `GET /api/plans/{planId}/priority-order` response shape, also what `PUT` returns on success
 * (same shape, refreshed). `matchesOrder`/`customWeightsActive` are complementary in practice: the
 * former is true exactly when the plan's current constraint weights are consistent with (derived
 * from) `order`'s ladder - the normal state after any successful PUT; the latter is true when an
 * admin customized weights in advanced mode away from any priority-order ladder, in which case
 * `order` is the backend's best-effort INFERRED ranking from those custom weights (still a full
 * permutation, just not authoritative over the actual weights until reset). `otherOverridesActive`
 * flags non-bucket constraint overrides (outside the four priority buckets entirely) independent of
 * either of those. `updatedAt` is `null` until the order has ever been explicitly saved.
 */
export interface PriorityOrderView {
  order: PriorityKey[];
  /** The backend's fixed default ranking (TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL) -
   *  currently unread by the frontend (no consumer derives anything from it yet). Reserved for a
   *  future "Återställ till standardordning" affordance distinct from the existing
   *  {@link PrioritiesPanel}'s "Återställ till prioriteringsordning" (which restores weights to
   *  match `order`, not `defaultOrder`) - deliberately not wired up in v0.6.0 F3/its review fixes. */
  defaultOrder: PriorityKey[];
  matchesOrder: boolean;
  customWeightsActive: boolean;
  otherOverridesActive: boolean;
  staleSinceLastRun: boolean;
  updatedAt: string | null;
  priorities: PriorityRowView[];
}

export const priorityOrderKey = (planId: string) => ["plans", planId, "priority-order"] as const;

/** Pre-F3 alias, kept so OptimizePanelSimple.tsx's existing import/usage (and its doc comments,
 *  which still name this function) doesn't need churn - same query, same cache entry as
 *  {@link usePriorityOrder} (identical query key), just narrower typing was assumed at that call
 *  site (only `.customWeightsActive` is read there). */
export const priorityOrderStatusKey = priorityOrderKey;

/** The full priority-order view (F3's real Prioriteringar screen). `usePriorityOrderStatus` below is
 *  the exact same query under the exact same key - both hooks share one cache entry, they're not two
 *  separate network round-trips. */
export function usePriorityOrder(planId: string | undefined) {
  return useQuery({
    queryKey: priorityOrderKey(planId ?? ""),
    queryFn: () => api.get<PriorityOrderView>(`/api/plans/${planId}/priority-order`),
    enabled: planId !== undefined,
  });
}

/** Back-compat name for OptimizePanelSimple's pre-F3 usage - see this module's other doc comments.
 *  A 404/erroring endpoint still degrades the same way it always did there (TanStack Query leaves
 *  `.data` `undefined` on a failed query with no `throwOnError`; the caller only renders its hint
 *  when `data?.customWeightsActive` is truthy). */
export const usePriorityOrderStatus = usePriorityOrder;

/**
 * `PUT /api/plans/{planId}/priority-order` - saves a new 4-key permutation. The backend validates
 * the body is a true permutation of the four {@link PriorityKey} values (400 with a Swedish message
 * otherwise - never re-validated client-side beyond the defensive `isPermutation` check callers
 * already run before invoking this) and, on success, restores every one of the six underlying bucket
 * constraint keys to enabled+SOFT at their ladder weights - i.e. saving an order always clears
 * `customWeightsActive` for the buckets, matching {@link PriorityOrderView}'s own doc comment.
 * Response is the same GET shape, refreshed.
 *
 * v0.6.0 F3 review fix (FIX 2, BLOCKER, PUT sequencing): deliberately does NOT write `data` into this
 * query's cache here any more - a rapid sequence of saves (debounce coalescing aside, e.g. an autosave
 * racing a manual "Återställ") can have two PUTs in flight at once and resolve out of order, and this
 * hook has no way to tell an in-order response from a stale one. That token-based staleness check
 * lives in PrioritiesPanel.tsx (mirrors src/lib/uiMode/useUiMode.ts's B4 fix) - the cache write, the
 * confirmed-order ref update, and the "Sparat ✓" status all happen there, gated on the response still
 * being the latest attempt.
 *
 * v0.6.0 F3 review fix (FIX 8, MINOR, symmetric cache invalidation): every successful PUT here
 * rewrites all six bucket constraint weights server-side (see this doc comment above) - invalidate
 * `constraintWeightsKey` so a mounted ConstraintWeightsTable refetches instead of showing stale
 * pre-save weights. The reverse direction (a constraint-weights PUT invalidating this module's
 * `priorityOrderKey`) lives in api/constraintWeights.ts's `useUpdateConstraintWeights`.
 */
export function useSetPriorityOrder(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (order: PriorityKey[]) => api.put<PriorityOrderView>(`/api/plans/${planId}/priority-order`, { order }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: constraintWeightsKey(planId) });
    },
  });
}
