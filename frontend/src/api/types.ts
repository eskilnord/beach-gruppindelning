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

/** §14.4 "Ta hänsyn till tidigare sparade planer" checkboxes - all default false/absent server-side
 *  (BlockingOptions.NONE, see SolveController#blockingOf), the MVP note ("bör minst stödja
 *  blockering av personer och tränare") is honored by the Optimeringsvy defaulting
 *  blockPlayers+blockCoaches to true client-side rather than in the backend contract itself. */
export interface BlockingSelectionRequest {
  blockPlayers?: boolean;
  blockCoaches?: boolean;
  blockCourts?: boolean;
  conflictsAsWarnings?: boolean;
}

/** Hand-written request body for `POST .../solve`; mirrors backend SolveController.SolveRequest. */
export interface SolveRequestBody {
  profile: SolveProfile;
  optimize?: OptimizeSelectionRequest;
  blocking?: BlockingSelectionRequest;
}

/** {@code POST/GET .../solve} responses now derive from a concrete `SolveResponse` schema
 *  (SolveController was changed from `ResponseEntity<?>` to `ResponseEntity<SolveResponse>` as part
 *  of the M8 typegen fix - see OpenApiSchemaTest) - only `runId`/`status` are read by
 *  `api/solve.ts#useStartSolve`, the rest narrowed away below. */
export type StartSolveResponse = WithRequired<components["schemas"]["SolveResponse"], "runId" | "status">;
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

export type WhatIfMoveRequest = components["schemas"]["WhatIfMoveRequest"];
export type WhatIfWhyNotRequest = components["schemas"]["WhyNotRequest"];

/** {@code POST /api/plans/{planId}/assignments/{pid}/move} request body. The schema/naming collision
 *  that used to force this to be entirely hand-written (springdoc merging `AssignmentController` and
 *  `WhatIfController`'s same-simple-name `MoveRequest` records) was fixed backend-side as part of the
 *  M8 typegen cleanup (renamed to `ApplyMoveRequest`/`WhatIfMoveRequest`, see `OpenApiSchemaTest`) -
 *  but `components["schemas"]["ApplyMoveRequest"]` still types `groupId` as `string | undefined`,
 *  which can't express the explicit `groupId: null` this app sends to move a player to the kölista
 *  (see `api/assignments.ts#useMoveAssignment`) - kept hand-written for that one field's nullability. */
export interface MoveAssignmentRequest {
  groupId: string | null;
}

// --- M8: Sparade planer / Export / Säsongsvy-konflikter ---

/** Legal `SavedPlan.status` values (spec §14.2), draft->saved->locked->published->archived - see
 *  `savedplan/SavedPlanLifecycle.LEGAL_TRANSITIONS` backend-side for the transition table this
 *  frontend's `savedPlanActions.ts` mirrors. */
export type SavedPlanStatus = "draft" | "saved" | "locked" | "published" | "archived";

/** `GET .../saved-plans` list item - note the backend returns the raw domain record here (including
 *  `snapshotJson` as an opaque string), NOT the same shape as the detail view below. */
export type SavedPlan = WithRequired<
  components["schemas"]["SavedPlan"],
  "id" | "activityPlanId" | "name" | "status" | "createdAt" | "updatedAt"
>;

/** `POST`/`GET one`/`PATCH .../saved-plans/{id}` - adds a parsed `snapshot` object in place of the
 *  list view's raw `snapshotJson` string (backend: `SavedPlanDetailView`). */
export type SavedPlanDetailView = WithRequired<
  components["schemas"]["SavedPlanDetailView"],
  "id" | "activityPlanId" | "name" | "status" | "createdAt" | "updatedAt"
>;

export type CreateSavedPlanRequest = components["schemas"]["SaveRequest"];
export type SavedPlanStatusRequest = components["schemas"]["StatusRequest"];

/** Accepted values for the Export tab's format/layout pickers (spec §20.1) - mirrors
 *  `ExportService.FORMAT_*`/`LAYOUT_*` constants exactly (lowercase). `layout=grouped` combined with
 *  `format=csv` is rejected by the backend with 400 (`ExportService#export`: "a csv has no
 *  sheets/blocks") - `exportForm.ts` keeps the UI from ever sending that combination. */
export type ExportFormat = "xlsx" | "csv";
export type ExportLayout = "grouped" | "flat";

/** Hand-written, NOT derived from `components["schemas"]`: `GET .../export` and
 *  `.../export/anonymized` return `ResponseEntity<byte[]>` (binary download, spec §20/§21.3), which
 *  springdoc/openapi-typescript has no JSON schema for - the frontend instead reads the filename off
 *  the `Content-Disposition` response header (see `api/client.ts#apiDownload`). */
export interface ExportDownload {
  blob: Blob;
  filename: string;
}
