/**
 * Domain types for the M1 REST surface, re-exported from the openapi-typescript-generated
 * src/api/schema.d.ts (single source of truth: `npm run typegen` regenerates schema.d.ts from the
 * running backend's /v3/api-docs). Kept as small named aliases here so the rest of the app never
 * spells out `components["schemas"][...]` inline.
 */
import type { components } from "./schema";

// springdoc marks every Java record component as an optional schema property (no @NotNull/
// @Schema(required=true) annotations in the M1 backend), so openapi-typescript generates every
// field as `T | undefined`. The fields below are always populated by the backend in practice (see
// the controllers in backend/src/main/java/se/klubb/groupplanner/api) — narrowing them here keeps
// the rest of the app from having to null-check ids/names/timestamps everywhere.
type WithRequired<T, K extends keyof T> = Omit<T, K> & Required<Pick<T, K>>;

export type SeasonPlan = WithRequired<
  components["schemas"]["SeasonPlan"],
  "id" | "name" | "status" | "createdAt" | "updatedAt"
>;
export type ActivityPlan = WithRequired<
  components["schemas"]["ActivityPlan"],
  "id" | "seasonPlanId" | "name" | "status" | "createdAt" | "updatedAt"
>;

export type CreateSeasonPlanRequest = components["schemas"]["CreateSeasonPlanRequest"];
export type UpdateSeasonPlanRequest = components["schemas"]["UpdateSeasonPlanRequest"];

export type CreateActivityPlanRequest = components["schemas"]["CreateActivityPlanRequest"];
export type UpdateActivityPlanRequest = components["schemas"]["UpdateActivityPlanRequest"];
