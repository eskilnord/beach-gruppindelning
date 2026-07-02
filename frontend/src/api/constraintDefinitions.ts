import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { ConstraintDefinition } from "./types";

/** Read-only listing of the 24 standard constraints (spec §10) with their seeded defaults - used by
 *  the Konfiguration section's "Återställ till standard" action, which needs the constraint's
 *  original default weight/classification/enabled state (the per-plan `constraint-weights` endpoint
 *  only returns the currently-effective merged value, not the default it would fall back to). */
export function useConstraintDefinitions() {
  return useQuery({
    queryKey: ["constraint-definitions"],
    queryFn: () => api.get<ConstraintDefinition[]>("/api/constraint-definitions"),
  });
}
