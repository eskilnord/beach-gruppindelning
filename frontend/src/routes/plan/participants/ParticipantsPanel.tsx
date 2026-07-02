import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, Text, TextInput, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import type { ColDef, ICellRendererParams } from "ag-grid-community";
import { DataGrid } from "../../../components/DataGrid";
import { useParticipants, useRecomputeLevels } from "../../../api/participants";
import { usePersons } from "../../../api/persons";
import { useAnonymizeAllComments } from "../../../api/comments";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
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

function CommentCell(props: ICellRendererParams<ParticipantRow>) {
  const hasComment = Boolean(props.data?.importedComment && props.data.importedComment.trim().length > 0);
  if (!hasComment) {
    return null;
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
      { headerName: sv.participants.columns.comment, field: "importedComment", width: 120, cellRenderer: CommentCell },
    ],
    [],
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

  return (
    <Card withBorder padding="lg">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{sv.participants.heading}</Title>
        <Group>
          <Button variant="default" onClick={() => navigate(`/plans/${planId}/import`)}>
            {sv.participants.importButton}
          </Button>
          {!isEmpty && (
            <>
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
            </>
          )}
        </Group>
      </Group>

      {isEmpty && <Text c="dimmed">{sv.participants.empty}</Text>}

      {!isEmpty && (
        <>
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
