import { useMutation } from "@tanstack/react-query";
import { api } from "./client";
import type { WhatIfMoveRequest, WhatIfMoveResponse, WhatIfWhyNotRequest, WhatIfWhyNotResponse } from "./types";

/**
 * What-if endpoints (spec §18, docs/design/04-solver.md §12/§14.2): pure evaluation, no mutation -
 * modeled as mutations (not queries) because they're POSTed on demand (target-group change / "Varför
 * inte grupp X?" click) rather than cached against a stable key, mirroring how the rest of this
 * codebase already treats other on-demand POST evaluations. Neither hook invalidates any query cache.
 */
export function useWhatIfMove(planId: string) {
  return useMutation({
    mutationFn: (body: WhatIfMoveRequest) => api.post<WhatIfMoveResponse>(`/api/plans/${planId}/whatif/move`, body),
  });
}

/** "Varför inte grupp X?" (spec §17.2) - the guaranteed answer path for ANY group the user picks,
 *  not just the automatic candidate set already shown in `alternatives[]` (design §11.5). */
export function useWhatIfWhyNot(planId: string) {
  return useMutation({
    mutationFn: (body: WhatIfWhyNotRequest) => api.post<WhatIfWhyNotResponse>(`/api/plans/${planId}/whatif/why-not`, body),
  });
}
