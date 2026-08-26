import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, Stack, Text, TextInput, Title, Tooltip } from "@mantine/core";
import { IconCircleCheck, IconUsers } from "@tabler/icons-react";
import { notifications } from "@mantine/notifications";
import type { ColDef, ICellRendererParams } from "ag-grid-community";
import { DataGrid } from "../../../components/DataGrid";
import { useParticipants, useRecomputeLevels } from "../../../api/participants";
import { usePersons } from "../../../api/persons";
import { useAnonymizeAllComments } from "../../../api/comments";
import { usePlanCommentSuggestions } from "../../../api/commentSuggestions";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { EmptyState } from "../../../components/EmptyState";
import { AdvancedOnly, SimpleOnly } from "../../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../../lib/uiMode/useUiMode";
import { describeLevelConfidence } from "./levelConfidence";
import { ParticipantDrawer } from "./ParticipantDrawer";
import type { ParticipantRow } from "./participantRow";

function dash(value: unknown): string {
  return value === null || value === undefined || value === "" ? "—" : String(value);
}

function LevelCell(props: ICellRendererParams<ParticipantRow>) {
  const badge = describeLevelConfidence(props.data?.levelConfidence);
  const level = props.data?.estimatedLevel;
  return (
    <Group gap={6} wrap="nowrap" h="100%" align="center">
      <Text size="sm">{level != null ? Math.round(level) : "—"}</Text>
      <Badge size="xs" color={badge.color} variant="light">
        {badge.label}
      </Badge>
    </Group>
  );
}

function WaitlistedCell(props: ICellRendererParams<ParticipantRow>) {
  if (!props.data?.waitlisted) {
    return null;
  }
  return (
    <Badge color="yellow" variant="light">
      {sv.participants.waitlistedBadge}
    </Badge>
  );
}

function ReviewCell(props: ICellRendererParams<ParticipantRow>) {
  if (!props.data?.manualReviewFlag) {
    return null;
  }
  return (
    <Tooltip label={sv.participants.needsReviewTooltip}>
      <Badge color="red" variant="light">
        {sv.participants.columns.needsReview}
      </Badge>
    </Tooltip>
  );
}

function DoneCell(props: ICellRendererParams<ParticipantRow>) {
  if (!props.data?.reviewedDone) {
    return null;
  }
  return (
    <Tooltip label={sv.participants.doneTooltip}>
      <Group h="100%" align="center">
        <span role="img" aria-label={sv.participants.doneTooltip}>
          <IconCircleCheck size={18} color="var(--mantine-color-green-6)" />
        </span>
      </Group>
    </Tooltip>
  );
}

interface CommentCellParams {
  /** participantId -> unapplied "Tolkningsförslag" count (WP2), from the plan-level suggestions
   *  query - `undefined` while that query is still loading, in which case the cell falls back to
   *  the plain dot badge rather than implying "no suggestions". */
  suggestionCounts: Map<string, number> | undefined;
}

function CommentCell(props: ICellRendererParams<ParticipantRow> & CommentCellParams) {
  const isSimple = useIsSimpleMode();
  const hasComment = Boolean(props.data?.importedComment && props.data.importedComment.trim().length > 0);
  if (!hasComment) {
    return null;
  }
  const count = props.data ? props.suggestionCounts?.get(props.data.id) : undefined;
  // B18.1 (v0.6.0 audit-fix batch B): the plan-level suggestions-count endpoint
  // (usePlanCommentSuggestions -> ParticipantSuggestionCount) returns a single total per
  // participant with no per-kind breakdown (see api/commentSuggestions.ts's "comment minimization"
  // doc comment). SIMPLE mode hides COACH_* suggestions entirely (CommentSuggestionList's
  // visibleSuggestionKinds), so that raw total can overcount what SIMPLE would actually show if the
  // drawer were opened - rather than promise a possibly-wrong N, SIMPLE always falls back to the
  // plain dot indicator below, never a number.
  if (!isSimple && count && count > 0) {
    return (
      <Tooltip label={sv.participants.suggestionCountTooltip(count)}>
        <Badge color="blue" variant="filled">
          {count}
        </Badge>
      </Tooltip>
    );
  }
  return (
    <Tooltip label={sv.participants.commentTooltip}>
      <Badge color="blue" variant="dot">
        {sv.participants.columns.comment}
      </Badge>
    </Tooltip>
  );
}

/**
 * Deltagarvy (spec §19.4, replaces the M3 basic table): an AG Grid of every participant with a
 * toolbar (import link, recompute-levels, anonymize-comments, quick filter) and a row-click detail
 * drawer for editing structured + custom fields (ParticipantDrawer.tsx).
 */
export function ParticipantsPanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const participants = useParticipants(planId);
  const persons = usePersons();
  const recomputeLevels = useRecomputeLevels(planId ?? "");
  const anonymizeAll = useAnonymizeAllComments(planId ?? "");
  const planSuggestions = usePlanCommentSuggestions(planId);

  const [quickFilter, setQuickFilter] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [anonymizeOpen, setAnonymizeOpen] = useState(false);
  const [searchParams] = useSearchParams();

  const personName = (personId: string): string => {
    const person = persons.data?.find((candidate) => candidate.id === personId);
    if (!person) {
      return personId;
    }
    return person.displayName || `${person.firstName} ${person.lastName}`.trim();
  };

  const rows: ParticipantRow[] = useMemo(
    () => (participants.data ?? []).map((participant) => ({ ...participant, name: personName(participant.personId) })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [participants.data, persons.data],
  );

  // Ctrl/Cmd+F player search (PlayerSearchSpotlight.tsx) falls back to `?participant=<id>` when the
  // plan has no groups yet (nothing solved to jump to in Resultatvy) - auto-open that participant's
  // detail drawer once the row data is in.
  useEffect(() => {
    const participantId = searchParams.get("participant");
    if (participantId && rows.some((row) => row.id === participantId)) {
      setSelectedId(participantId);
    }
  }, [searchParams, rows]);

  // Review fix (MAJOR 6): the backend now returns counts-only entries (already filtered to
  // unapplied > 0) - no client-side re-filtering of full suggestion detail needed anymore.
  const suggestionCounts = useMemo(() => {
    if (!planSuggestions.data) {
      return undefined;
    }
    const counts = new Map<string, number>();
    for (const entry of planSuggestions.data) {
      counts.set(entry.participantId, entry.suggestionCount);
    }
    return counts;
  }, [planSuggestions.data]);

  const columnDefs: ColDef<ParticipantRow>[] = useMemo(
    () => [
      { headerName: sv.participants.columns.name, field: "name", flex: 1.4, minWidth: 160 },
      {
        headerName: sv.participants.columns.ranking,
        field: "rankingPoints",
        width: 110,
        valueFormatter: (params) => dash(params.value),
      },
      {
        headerName: sv.participants.columns.previousGroup,
        field: "previousGroupName",
        width: 150,
        valueFormatter: (params) => dash(params.value),
      },
      { headerName: sv.participants.columns.level, field: "estimatedLevel", width: 150, cellRenderer: LevelCell },
      {
        headerName: sv.participants.columns.manualLevelScore,
        field: "manualLevelScore",
        width: 170,
        valueFormatter: (params) => dash(params.value),
      },
      { headerName: sv.participants.columns.waitlisted, field: "waitlisted", width: 120, cellRenderer: WaitlistedCell },
      {
        headerName: sv.participants.columns.needsReview,
        field: "manualReviewFlag",
        width: 150,
        cellRenderer: ReviewCell,
      },
      {
        headerName: sv.participants.columns.comment,
        field: "importedComment",
        width: 120,
        cellRenderer: CommentCell,
        cellRendererParams: { suggestionCounts } satisfies CommentCellParams,
      },
      { headerName: sv.participants.columns.done, field: "reviewedDone", width: 100, cellRenderer: DoneCell },
    ],
    [suggestionCounts],
  );

  if (participants.isLoading || persons.isLoading) {
    return <Loader size="sm" />;
  }
  if (participants.isError) {
    return (
      <Alert color="red">
        {participants.error instanceof ApiError ? participants.error.message : sv.participants.loadFailed}
      </Alert>
    );
  }

  const isEmpty = (participants.data ?? []).length === 0;
  const selectedParticipant = rows.find((row) => row.id === selectedId) ?? null;
  // v0.6.0 F4 (M-S4): "no level at all" - both the imported estimate AND a manual override are
  // absent. Feeds the summary strip below.
  const withoutLevelCount = rows.filter((row) => row.estimatedLevel == null && row.manualLevelScore == null).length;
  // B16 (v0.6.0 audit-fix batch B): feeds the "K av N klarmarkerade" segment of the SIMPLE summary
  // strip above.
  const reviewedCount = rows.filter((row) => row.reviewedDone).length;

  return (
    <Card withBorder padding="lg">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{sv.participants.heading}</Title>
        <Group>
          <Button variant="default" onClick={() => navigate(`/plans/${planId}/import`)}>
            {sv.participants.importButton}
          </Button>
          {/* B15 (v0.6.0 audit-fix batch B, P1): both actions are destructive/bulk (irreversible
              recompute overwrite, permanent comment anonymization) and confusing for a non-technical
              admin - ADVANCED-only, never rendered in SIMPLE mode. */}
          {!isEmpty && (
            <AdvancedOnly>
              <Button
                variant="default"
                loading={recomputeLevels.isPending}
                onClick={() =>
                  recomputeLevels.mutate(undefined, {
                    onSuccess: (result) =>
                      notifications.show({
                        color: "green",
                        message: sv.participants.recomputeLevelsSuccess(result.recomputedCount),
                      }),
                    onError: (error) =>
                      notifications.show({
                        color: "red",
                        title: sv.common.error,
                        message: error instanceof ApiError ? error.message : sv.participants.recomputeLevelsFailed,
                      }),
                  })
                }
              >
                {sv.participants.recomputeLevelsButton}
              </Button>
              <Button color="red" variant="outline" onClick={() => setAnonymizeOpen(true)}>
                {sv.participants.anonymizeButton}
              </Button>
            </AdvancedOnly>
          )}
        </Group>
      </Group>

      {isEmpty && <EmptyState icon={<IconUsers size={22} stroke={1.75} />} message={sv.participants.empty} />}

      {!isEmpty && (
        <>
          {/* v0.6.0 F4 (M-S4): summary strip, SIMPLE-only. Only "N deltagare" and "N utan nivå" are
              cheaply derivable from data ParticipantsPanel already has loaded (ParticipantProfile's
              own estimatedLevel/manualLevelScore) - "har önskat tid"/"kompisönskemål" would need
              per-participant field-value data this grid never bulk-loads (only usePlanCommentSuggestions'
              counts-only summary, which carries no per-kind breakdown - see commentSuggestions.ts's
              "comment minimization" doc comment), so those two segments are deliberately dropped
              rather than adding a new backend call. */}
          <SimpleOnly>
            {/* B16 (v0.6.0 audit-fix batch B, P1): step-framing heading/body, so a non-technical
                admin lands on this screen knowing WHY they're here and that "klarmarkerad" is
                optional bookkeeping, not a required gate. */}
            <Stack gap={2} mb="sm" data-testid="simple-participants-step-heading">
              <Title order={5}>{sv.simple.participants.stepHeading}</Title>
              <Text size="sm" c="dimmed">
                {sv.simple.participants.stepBody}
              </Text>
            </Stack>
            <Group gap={6} mb="sm" data-testid="simple-participants-summary">
              <Text size="sm">{sv.simple.participants.summary.total(rows.length)}</Text>
              <Text size="sm" c="dimmed">
                ·
              </Text>
              <Text size="sm" c={withoutLevelCount > 0 ? "orange" : undefined}>
                {sv.simple.participants.summary.withoutLevel(withoutLevelCount)}
              </Text>
              <Text size="sm" c="dimmed">
                ·
              </Text>
              <Text size="sm">{sv.simple.participants.summary.reviewed(reviewedCount, rows.length)}</Text>
            </Group>
          </SimpleOnly>

          <TextInput
            placeholder={sv.participants.quickFilterPlaceholder}
            value={quickFilter}
            onChange={(event) => setQuickFilter(event.currentTarget.value)}
            mb="sm"
            w={280}
          />
          <DataGrid<ParticipantRow>
            rowData={rows}
            columnDefs={columnDefs}
            getRowId={(params) => params.data.id}
            quickFilterText={quickFilter}
            onRowClicked={(event) => event.data && setSelectedId(event.data.id)}
          />
        </>
      )}

      {planId && (
        <ParticipantDrawer
          planId={planId}
          participant={selectedParticipant}
          allParticipants={rows}
          onClose={() => setSelectedId(null)}
        />
      )}

      <DeleteConfirmModal
        opened={anonymizeOpen}
        title={sv.participants.anonymizeModal.title}
        message={sv.participants.anonymizeModal.message}
        confirmLabel={sv.participants.anonymizeModal.confirm}
        loading={anonymizeAll.isPending}
        onClose={() => setAnonymizeOpen(false)}
        onConfirm={() =>
          anonymizeAll.mutate(undefined, {
            onSuccess: (result) => {
              setAnonymizeOpen(false);
              notifications.show({
                color: "green",
                message: sv.participants.anonymizeModal.success(result.clearedCount),
              });
            },
            onError: (error) => {
              notifications.show({
                color: "red",
                title: sv.common.error,
                message: error instanceof ApiError ? error.message : sv.participants.anonymizeModal.failed,
              });
            },
          })
        }
      />
    </Card>
  );
}
