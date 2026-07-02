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

export type Person = WithRequired<
  components["schemas"]["Person"],
  "id" | "firstName" | "lastName" | "canBeParticipant" | "canBeCoach"
>;
export type ParticipantProfile = WithRequired<
  components["schemas"]["ParticipantProfile"],
  "id" | "personId" | "activityPlanId" | "manualReviewFlag" | "waitlisted"
>;
export type FieldDefinition = WithRequired<
  components["schemas"]["FieldDefinition"],
  "id" | "key" | "label" | "fieldType" | "isStandard" | "storageKind" | "affectsOptimization" | "constraintType"
>;

export type CreateFieldDefinitionRequest = components["schemas"]["CreateFieldDefinitionRequest"];
export type UpdateFieldDefinitionRequest = components["schemas"]["UpdateFieldDefinitionRequest"];

export type ConstraintDefinition = WithRequired<
  components["schemas"]["ConstraintDefinition"],
  "key" | "label" | "description" | "constraintCategory" | "defaultWeight" | "hardOrSoft" | "enabled"
>;
export type ConstraintWeightView = WithRequired<
  components["schemas"]["ConstraintWeightView"],
  "key" | "label" | "description" | "constraintCategory" | "hardOrSoft" | "weight" | "enabled" | "overridden"
>;
export type ConstraintWeightOverrideRequest = components["schemas"]["ConstraintWeightOverrideRequest"];

export type FieldValueView = WithRequired<
  components["schemas"]["FieldValueView"],
  "fieldDefinitionId" | "key" | "label" | "fieldType"
>;

export type RecomputeLevelsResult = WithRequired<
  components["schemas"]["RecomputeLevelsResult"],
  "recomputedCount"
>;
export type AnonymizeResult = WithRequired<components["schemas"]["AnonymizeResult"], "clearedCount">;

// --- M5: Resurser / Tränare / Kapacitet ---

export type TimeSlot = WithRequired<
  components["schemas"]["TimeSlot"],
  "id" | "activityPlanId" | "startTime" | "endTime" | "durationMinutes" | "label"
>;
export type CreateTimeSlotRequest = components["schemas"]["CreateTimeSlotRequest"];

export type TrainingBlockView = WithRequired<
  components["schemas"]["TrainingBlockView"],
  "id" | "timeSlotId" | "courtId" | "courtName" | "activityPlanId" | "active" | "locked"
>;

/** Grouped view backing the Resursvy (spec §19.6): one entry per time slot with its blocks. Hand-
 *  written rather than derived via WithRequired so the nested fields resolve to the already-narrowed
 *  TimeSlot/TrainingBlockView aliases above, not the schema's raw optional-everywhere shape. */
export interface SlotBlocksView {
  timeSlot: TimeSlot;
  blocks: TrainingBlockView[];
}

export type CoachProfile = WithRequired<
  components["schemas"]["CoachProfile"],
  "id" | "personId" | "activityPlanId" | "canAlsoTrainAsParticipant"
>;
export type CreateCoachRequest = components["schemas"]["CreateCoachRequest"];

export type AvailabilityKind = "AVAILABLE" | "UNAVAILABLE" | "PREFERRED";
export type AvailabilityEntry = WithRequired<components["schemas"]["AvailabilityEntry"], "timeSlotId" | "kind">;

export type TimeSlotCapacityView = WithRequired<
  components["schemas"]["TimeSlotCapacityView"],
  "timeSlotId" | "label" | "activeBlockCount" | "coachesAvailableCount" | "coachesPreferredCount"
>;

/** Hand-written for the same reason as SlotBlocksView above: `perTimeSlot` needs to resolve to the
 *  already-narrowed TimeSlotCapacityView, not the schema's raw optional-everywhere element type.
 *  `Omit<..., "perTimeSlot">` before intersecting is required, not just tidiness - intersecting two
 *  object types that both declare the same key intersects their *property types* rather than
 *  overriding, which would silently keep the raw `TimeSlotCapacityView[] | undefined` element type
 *  alive alongside the narrowed one. */
export type CapacityResponse = Omit<
  WithRequired<
    components["schemas"]["CapacityResponse"],
    | "participantCount"
    | "waitlistedCount"
    | "activeTrainingBlockCount"
    | "waitlistRisk"
    | "waitlistMessage"
    | "coachCount"
    | "groupsRequiringCoachEstimate"
    | "coachShortageRisk"
    | "coachShortageMessage"
  >,
  "perTimeSlot"
> & {
  perTimeSlot: TimeSlotCapacityView[];
};

// --- M6b: Optimering / Resultat / Planeringskarta ---

export type TrainingGroup = WithRequired<
  components["schemas"]["TrainingGroup"],
  "id" | "activityPlanId" | "name" | "requiredCoachCount" | "locked"
>;

export type PlayerAssignment = WithRequired<
  components["schemas"]["PlayerAssignment"],
  "id" | "participantProfileId" | "locked" | "source"
>;
export type CoachAssignment = WithRequired<
  components["schemas"]["CoachAssignment"],
  "id" | "coachProfileId" | "groupId" | "locked" | "source"
>;

/** Hand-written for the same reason as SlotBlocksView above (nested list narrowing). */
export interface AssignmentsView {
  players: PlayerAssignment[];
  coaches: CoachAssignment[];
}

export type SolveProfile = "FAST" | "NORMAL" | "THOROUGH" | "GREEDY";

export interface OptimizeSelectionRequest {
  players?: boolean;
  schedule?: boolean;
  coaches?: boolean;
}

/** Hand-written request body for `POST .../solve` (blocking/§14.4 omitted - out of scope for this
 *  milestone's UI, see docs/plan.md M8 "spara plan"). */
export interface SolveRequestBody {
  profile: SolveProfile;
  optimize?: OptimizeSelectionRequest;
}

export type StartSolveResponse = WithRequired<components["schemas"]["StartSolveResponse"], "runId" | "status">;
export type CancelSolveResponse = WithRequired<components["schemas"]["CancelSolveResponse"], "finalRunId">;

/** `GET .../solve/status` (docs/design/04-solver.md §14.2) - score/progress fields are `null` when
 *  no run has ever happened for this plan, hence no WithRequired narrowing beyond `status`. */
export type SolveStatus = WithRequired<components["schemas"]["SolveStatus"], "status">;

/** {@code GET /api/plans/{planId}/runs} (körhistorik) - {@code resultSummaryJson} is a small JSON
 *  blob ({@code {hard,medium,soft,feasible,unassignedCount}}), parsed client-side (see
 *  `optimize/runSummary.ts`) rather than modeled as nested schema here. */
export type OptimizationRun = WithRequired<
  components["schemas"]["OptimizationRun"],
  "id" | "activityPlanId" | "status" | "startedAt"
>;

export interface RunResultSummary {
  hard: number;
  medium: number;
  soft: number;
  feasible: boolean;
  unassignedCount: number;
}

export type ConflictUsage = WithRequired<
  components["schemas"]["ConflictUsage"],
  "planId" | "planName" | "groupName" | "time"
>;
export type SeasonConflict = Omit<
  WithRequired<components["schemas"]["SeasonConflict"], "type" | "severity">,
  "usages"
> & {
  usages: ConflictUsage[];
};
