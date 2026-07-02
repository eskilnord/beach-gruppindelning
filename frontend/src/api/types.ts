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

/** Hand-written, NOT derived from {@code components["schemas"]}: {@code SolveController#solve}
 *  returns {@code ResponseEntity<?>} (deliberately - it dynamically returns either a
 *  `StartSolveResponse` or the synchronous-GREEDY-profile `GreedySolveResponse` shape depending on
 *  request content), which springdoc can't statically resolve to a concrete schema - the generated
 *  `POST /api/plans/{planId}/solve` response type collapses to `Record<string, never>` instead of a
 *  named component. Both possible response shapes share `runId`/`status`, the only two fields this
 *  app ever reads from the mutation result (see `api/solve.ts#useStartSolve`) - backend is out of
 *  scope for this milestone's frontend half, so hand-maintained here instead. */
export interface StartSolveResponse {
  runId: string;
  status: string;
}
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

// --- M7: Förklarbarhet / What-if ---

/** {@code origin} entries on an {@link AlternativeGroupView} (design §11.3's union-rule labels). */
export type AlternativeOrigin = "FRIEND_WISH" | "COACH_WISH" | "PREVIOUS_GROUP" | "TOP_SCORE";

/** {@code verdict} on an {@link AlternativeGroupView} - whether the candidate group would break a
 *  hard rule, improve, worsen, or leave total score exactly unchanged (NEUTRAL, an M7-review
 *  backend extension - narrative "påverkar inte totalpoängen") relative to the current placement. */
export type AlternativeVerdict = "WOULD_BREAK_HARD" | "BETTER" | "NEUTRAL" | "WORSE";

/** {@code level} on ConstraintSummaryView/AppliedWeightView - reuses the same HARD/SOFT/MEDIUM
 *  vocabulary as {@link sv.hardOrSoft}, but lower-cased for the spec's own "soft 80"/"hard" badge
 *  phrasing (kravspec §17.2 worked example uses these English tokens verbatim mid-Swedish-sentence). */
export type ConstraintLevel = "HARD" | "SOFT" | "MEDIUM";

// NOTE: unlike the plain `WithRequired` usages elsewhere in this file, every composite type below
// (one whose schema has a field pointing at ANOTHER schema, e.g. PersonExplanationResponse.
// alternatives -> AlternativeGroupView[]) uses the same `Omit<WithRequired<...>, nestedKeys> &
// {nestedKey: NarrowedType}` idiom already established by SlotBlocksView/CapacityResponse above:
// `WithRequired` alone only un-optionals a field, it does NOT rewrite a nested array/object field's
// ELEMENT type from the schema's raw all-optional shape to this file's own narrowed alias of the
// same name - leaving it un-rewritten silently defeats every required-field narrowing one level
// down (a real bug caught by `tsc`, not just style).

export type ScoreDeltaView = WithRequired<components["schemas"]["ScoreDeltaView"], "hard" | "medium" | "soft">;
export type ConstraintMessageView = WithRequired<components["schemas"]["ConstraintMessageView"], "key" | "messageSv">;
export type FactorView = WithRequired<components["schemas"]["FactorView"], "messageSv">;
export type AppliedWeightView = WithRequired<
  components["schemas"]["AppliedWeightView"],
  "key" | "label" | "level" | "weight"
>;
export type BrokenWishView = WithRequired<components["schemas"]["BrokenWishView"], "key" | "messageSv" | "weightApplied">;
export type SelectedGroupView = WithRequired<components["schemas"]["SelectedGroupView"], "groupId" | "name" | "size">;
export type WaitlistBlockerView = WithRequired<components["schemas"]["WaitlistBlockerView"], "groupId" | "name" | "blockerSv">;

export type AlternativeGroupView = Omit<
  WithRequired<
    components["schemas"]["AlternativeGroupView"],
    "groupId" | "name" | "origin" | "verdict" | "scoreDelta" | "newlyBroken" | "newlyFixed" | "narrativeSv"
  >,
  "scoreDelta" | "newlyBroken" | "newlyFixed"
> & {
  scoreDelta: ScoreDeltaView;
  newlyBroken: ConstraintMessageView[];
  newlyFixed: ConstraintMessageView[];
};

export type WaitlistView = Omit<WithRequired<components["schemas"]["WaitlistView"], "reasonSv" | "perGroupBlockers">, "perGroupBlockers"> & {
  perGroupBlockers: WaitlistBlockerView[];
};

export type PersonExplanationResponse = Omit<
  WithRequired<
    components["schemas"]["PersonExplanationResponse"],
    | "runId"
    | "basedOnRevision"
    | "currentRevision"
    | "stale"
    | "participantProfileId"
    | "name"
    | "positiveFactors"
    | "negativeFactors"
    | "brokenWishes"
    | "appliedWeights"
    | "alternatives"
  >,
  "selectedGroup" | "positiveFactors" | "negativeFactors" | "brokenWishes" | "appliedWeights" | "alternatives" | "waitlist"
> & {
  selectedGroup?: SelectedGroupView;
  positiveFactors: FactorView[];
  negativeFactors: FactorView[];
  brokenWishes: BrokenWishView[];
  appliedWeights: AppliedWeightView[];
  alternatives: AlternativeGroupView[];
  waitlist?: WaitlistView;
};

export type GroupCoachView = WithRequired<components["schemas"]["GroupCoachView"], "coachProfileId" | "name">;
export type GroupBlockView = WithRequired<components["schemas"]["GroupBlockView"], "trainingBlockId" | "label">;
export type GroupMatchView = Omit<
  WithRequired<components["schemas"]["GroupMatchView"], "key" | "messageSv" | "scoreImpact">,
  "scoreImpact"
> & { scoreImpact: ScoreDeltaView };
export type GroupMemberBrokenWishView = WithRequired<
  components["schemas"]["GroupMemberBrokenWishView"],
  "participantProfileId" | "name" | "messageSv"
>;
export type GroupExplanationResponse = Omit<
  WithRequired<
    components["schemas"]["GroupExplanationResponse"],
    "runId" | "basedOnRevision" | "currentRevision" | "stale" | "groupId" | "name" | "size" | "warnings" | "matches" | "membersWithBrokenWishes"
  >,
  "coach" | "block" | "matches" | "membersWithBrokenWishes"
> & {
  coach?: GroupCoachView;
  block?: GroupBlockView;
  matches: GroupMatchView[];
  membersWithBrokenWishes: GroupMemberBrokenWishView[];
};

export type ConstraintSummaryView = WithRequired<
  components["schemas"]["ConstraintSummaryView"],
  "key" | "label" | "level" | "weightApplied" | "scoreTotal" | "matchCount"
>;
export type HardViolationView = WithRequired<components["schemas"]["HardViolationView"], "key" | "messageSv">;
export type WaitlistEntryView = WithRequired<
  components["schemas"]["WaitlistEntryView"],
  "participantProfileId" | "name" | "priority" | "reasonSv"
>;
export type ProblematicGroupView = WithRequired<components["schemas"]["ProblematicGroupView"], "groupId" | "name" | "penaltySum">;
export type ManualReviewEntryView = WithRequired<
  components["schemas"]["ManualReviewEntryView"],
  "participantProfileId" | "name" | "reasonSv"
>;
export type PlanExplanationResponse = Omit<
  WithRequired<
    components["schemas"]["PlanExplanationResponse"],
    | "runId"
    | "basedOnRevision"
    | "currentRevision"
    | "stale"
    | "score"
    | "feasible"
    | "constraintSummaries"
    | "hardViolations"
    | "waitlist"
    | "problematicGroups"
    | "manualReview"
  >,
  "score" | "constraintSummaries" | "hardViolations" | "waitlist" | "problematicGroups" | "manualReview"
> & {
  score: ScoreDeltaView;
  constraintSummaries: ConstraintSummaryView[];
  hardViolations: HardViolationView[];
  waitlist: WaitlistEntryView[];
  problematicGroups: ProblematicGroupView[];
  manualReview: ManualReviewEntryView[];
};

export type GroupSizeChangeView = WithRequired<
  components["schemas"]["GroupSizeChangeView"],
  "groupId" | "name" | "from" | "to"
>;
export type LevelSpreadChangeView = WithRequired<
  components["schemas"]["LevelSpreadChangeView"],
  "groupId" | "name" | "from" | "to"
>;
export type WhatIfMoveResponse = Omit<
  WithRequired<
    components["schemas"]["WhatIfMoveResponse"],
    | "runId"
    | "basedOnRevision"
    | "currentRevision"
    | "stale"
    | "scoreDelta"
    | "wouldBreakHard"
    | "groupSizeChanges"
    | "levelSpreadChanges"
    | "newlyBroken"
    | "newlyFixed"
    | "suggestedActions"
  >,
  "scoreDelta" | "groupSizeChanges" | "levelSpreadChanges" | "newlyBroken" | "newlyFixed"
> & {
  scoreDelta: ScoreDeltaView;
  groupSizeChanges: GroupSizeChangeView[];
  levelSpreadChanges: LevelSpreadChangeView[];
  newlyBroken: ConstraintMessageView[];
  newlyFixed: ConstraintMessageView[];
};
export type WhatIfWhyNotResponse = Omit<
  WithRequired<components["schemas"]["WhatIfWhyNotResponse"], "runId" | "basedOnRevision" | "currentRevision" | "stale" | "alternative">,
  "alternative"
> & { alternative: AlternativeGroupView };

export type WhatIfMoveRequest = components["schemas"]["MoveRequest"];
export type WhatIfWhyNotRequest = components["schemas"]["WhyNotRequest"];

/** Hand-written, NOT derived from {@code components["schemas"]["MoveRequest"]}: springdoc collapses
 *  {@code AssignmentController.MoveRequest} and {@code WhatIfController.MoveRequest} into a single
 *  `MoveRequest` OpenAPI schema because both Java records share the same simple class name in
 *  different packages - only the what-if shape survives in the generated doc/schema.d.ts. The actual
 *  {@code POST /api/plans/{planId}/assignments/{pid}/move} endpoint still deserializes its own
 *  `record MoveRequest(String groupId)` correctly at runtime (see AssignmentController.java); this is
 *  a documentation-generation artifact only, not a runtime bug - backend is out of scope for this
 *  milestone's frontend half, so the type is hand-maintained here instead. */
export interface MoveAssignmentRequest {
  groupId: string | null;
}
