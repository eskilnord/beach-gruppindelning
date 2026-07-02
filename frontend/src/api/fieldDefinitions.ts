import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { FieldDefinition } from "./types";

const fieldDefinitionsKey = (planId: string) => ["plans", planId, "field-definitions"] as const;

/** Field definitions visible to a plan (spec §9) — global standard fields today (M1/M3 scope); used
 *  by the import mapping step to offer "customField:<key>" targets for CUSTOM-storage fields
 *  (Fältbyggaren's own CRUD UI arrives in M4). */
export function useFieldDefinitions(planId: string | undefined) {
  return useQuery({
    queryKey: fieldDefinitionsKey(planId ?? ""),
    queryFn: () => api.get<FieldDefinition[]>(`/api/plans/${planId}/field-definitions`),
    enabled: planId !== undefined,
  });
}
