import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, Button, Card, Loader, SegmentedControl, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { IconTrophy } from "@tabler/icons-react";
import { useAssignments } from "../../../api/assignments";
import { ApiError } from "../../../api/client";
import { useCoaches } from "../../../api/coaches";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useGroups } from "../../../api/groups";
import { useParticipantFieldValues, useParticipants } from "../../../api/participants";
import { usePersons } from "../../../api/persons";
import { usePlan } from "../../../api/plans";
import { useOptimizationRuns } from "../../../api/runs";
import { useTrainingBlocksForPlan } from "../../../api/trainingBlocks";
import { sv } from "../../../i18n/sv";
import { EmptyState } from "../../../components/EmptyState";
import { formatDateTime } from "../../../lib/formatDateTime";
import { parseResultSummary } from "../optimize/runSummary";
import { ExplainDrawer, type GroupOption } from "./explain/ExplainDrawer";
import { GroupExplainDrawer } from "./explain/GroupExplainDrawer";
import { WhatIfDialog } from "./explain/WhatIfDialog";
import { GroupCard } from "./GroupCard";
import { ScheduleView } from "./ScheduleView";
import { WaitlistCard, type WaitlistEntry } from "./WaitlistCard";

type ResultView = "cards" | "schedule";

function personDisplayName(persons: { id: string; displayName?: string; firstName?: string; lastName?: string }[] | undefined, personId: string | undefined): string {
  if (!personId) {
    return "";
  }
  const person = persons?.find((p) => p.id === personId);
  if (!person) {
    return personId;
  }
  return person.displayName || `${person.firstName ?? ""} ${person.lastName ?? ""}`.trim();
}

/**
 * Resultatvy (spec §19.10) with a Kort/Schema sub-view toggle (spec §19.11 note: Planeringskarta is
 * a sub-view of the Resultat tab, not its own route). Joins groups/assignments/participants/persons/
 * coaches/training-blocks client-side (the same pattern ParticipantsPanel already uses to resolve
 * person names) into per-group view models for GroupCard, plus the OPLACERAD/KÖLISTA bucket.
 */
export function ResultsPanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const [view, setView] = useState<ResultView>("cards");

  // Ctrl/Cmd+F player search (PlayerSearchSpotlight.tsx) navigates here with `?highlight=<id>` -
  // force the Kort view (only it renders per-member rows to scroll to/flash) and jump to the row
  // once the joined view model below is ready.
  const [searchParams] = useSearchParams();
  const highlightedParticipantId = searchParams.get("highlight");

  // M7 explain/what-if drawer & dialog state - lifted to this shared parent (rather than living
  // inside each GroupCard/WaitlistCard) so there is exactly ONE of each mounted at a time and the
  // "waitlisted friend" link in the explain drawer can jump straight to a different participant
  // anywhere in the plan by just updating this state.
  const [explainTarget, setExplainTarget] = useState<{ id: string; name: string } | null>(null);
  const [whatIfTarget, setWhatIfTarget] = useState<{ id: string; name: string; currentGroupId: string | null } | null>(null);
  const [explainGroup, setExplainGroup] = useState<{ id: string; name: string } | null>(null);

  const plan = usePlan(planId);
  const groups = useGroups(planId);
  const assignments = useAssignments(planId);
  const participants = useParticipants(planId);
  const persons = usePersons();
  const coaches = useCoaches(planId);
  const trainingBlocks = useTrainingBlocksForPlan(planId);
  const fieldDefinitions = useFieldDefinitions(planId);
  const runs = useOptimizationRuns(planId);

  const latestRun = runs.data?.[0];
  const latestRunId = latestRun?.id;
  const runStartedAtLabel = latestRun ? formatDateTime(latestRun.startedAt) : undefined;
  // v0.2.0 (COACH-OPTIONAL SOLVING): the run summary's `note` (present only when the run solved a
  // plan with zero coaches) is surfaced in the Resultatvy header too, so a user landing straight on
  // the results doesn't have to go back to Optimering to learn why no group has a coach.
  const latestRunNote = parseResultSummary(latestRun)?.note ?? null;

  const waitlistedParticipantIds = useMemo(
    () => (assignments.data?.players ?? []).filter((p) => p.groupId == null).map((p) => p.participantProfileId),
    [assignments.data],
  );
  const priorityFieldValues = useParticipantFieldValues(planId, waitlistedParticipantIds);

  const isLoading =
    plan.isLoading || groups.isLoading || assignments.isLoading || participants.isLoading || persons.isLoading ||
    coaches.isLoading || trainingBlocks.isLoading;
  const firstError = [plan, groups, assignments, participants, persons, coaches, trainingBlocks].find((q) => q.isError);

  const model = useMemo(() => {
    if (!groups.data || !assignments.data || !participants.data || !coaches.data || !trainingBlocks.data) {
      return null;
    }

    const participantById = new Map(participants.data.map((p) => [p.id, p]));
    const coachNameByProfileId = new Map(coaches.data.map((c) => [c.id, personDisplayName(persons.data, c.personId)]));
    const blockById = new Map<string, { courtName: string; timeSlotLabel: string }>();
    for (const slot of trainingBlocks.data) {
      for (const block of slot.blocks) {
        blockById.set(block.id, { courtName: block.courtName, timeSlotLabel: slot.timeSlot.label });
      }
    }

    const playersByGroupId = new Map<string, typeof assignments.data.players>();
    const waitlist: WaitlistEntry[] = [];
    for (const pa of assignments.data.players) {
      if (pa.groupId == null) {
        const participant = participantById.get(pa.participantProfileId);
        const priorityField = (fieldDefinitions.data ?? []).find((f) => f.constraintType === "PRIORITY");
        const priorityValue = priorityField
          ? priorityFieldValues[pa.participantProfileId]?.find((fv) => fv.fieldDefinitionId === priorityField.id)?.value
          : undefined;
        waitlist.push({
          participantProfileId: pa.participantProfileId,
          name: personDisplayName(persons.data, participant?.personId),
          level: participant?.estimatedLevel ?? null,
          priority: priorityValue != null ? String(priorityValue) : null,
        });
        continue;
      }
      const list = playersByGroupId.get(pa.groupId) ?? [];
      list.push(pa);
      playersByGroupId.set(pa.groupId, list);
    }
    waitlist.sort((a, b) => a.name.localeCompare(b.name, "sv"));

    const coachesByGroupId = new Map<string, typeof assignments.data.coaches>();
    for (const ca of assignments.data.coaches) {
      const list = coachesByGroupId.get(ca.groupId) ?? [];
      list.push(ca);
      coachesByGroupId.set(ca.groupId, list);
    }

    const sortedGroups = [...groups.data].sort((a, b) => {
      const orderA = a.groupOrder ?? Number.MAX_SAFE_INTEGER;
      const orderB = b.groupOrder ?? Number.MAX_SAFE_INTEGER;
      return orderA !== orderB ? orderA - orderB : a.name.localeCompare(b.name, "sv");
    });

    const coachNameByGroupId: Record<string, string | null> = {};
    for (const group of sortedGroups) {
      const groupCoaches = coachesByGroupId.get(group.id) ?? [];
      coachNameByGroupId[group.id] = groupCoaches.length > 0 ? coachNameByProfileId.get(groupCoaches[0].coachProfileId) ?? null : null;
    }

    return {
      sortedGroups,
      playersByGroupId,
      coachesByGroupId,
      coachNameByProfileId,
      coachNameByGroupId,
      blockById,
      participantById,
      waitlist,
    };
  }, [groups.data, assignments.data, participants.data, coaches.data, trainingBlocks.data, persons.data, fieldDefinitions.data, priorityFieldValues]);

  // `model` gets a brand-new object reference on every render (useParticipantFieldValues below
  // builds a fresh plain object each call), so this can't depend on `model` directly - that would
  // re-fire on every unrelated re-render (e.g. the user's own click on the Schema toggle) and force
  // the view back to "cards" every time. Guard with a ref so a given highlight id is only acted on
  // once, the moment its row first appears in the DOM.
  const highlightHandledRef = useRef<string | null>(null);
  useEffect(() => {
    if (!highlightedParticipantId || !model || highlightHandledRef.current === highlightedParticipantId) {
      return;
    }
    const row = document.getElementById(`participant-row-${highlightedParticipantId}`);
    if (!row) {
      return;
    }
    highlightHandledRef.current = highlightedParticipantId;
    setView("cards");
    row.scrollIntoView({ behavior: "smooth", block: "center" });
  });

  if (isLoading) {
    return <Loader size="sm" />;
  }
  if (firstError || !model) {
    return (
      <Alert color="red">{firstError?.error instanceof ApiError ? firstError.error.message : sv.results.loadFailed}</Alert>
    );
  }

  if (model.sortedGroups.length === 0) {
    return (
      <Card withBorder padding="xl">
        <Title order={4} mb="xs">
          {sv.results.heading}
        </Title>
        <EmptyState
          icon={<IconTrophy size={22} stroke={1.75} />}
          message={sv.results.empty}
          action={
            <Button variant="default" onClick={() => navigate(`/plans/${planId}/optimering`)}>
              {sv.plan.tabs.optimize}
            </Button>
          }
        />
      </Card>
    );
  }

  const allGroups: GroupOption[] = model.sortedGroups.map((g) => ({ id: g.id, name: g.name }));

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={4} mb="md">
          {sv.results.heading}
        </Title>
        <SegmentedControl
          value={view}
          onChange={(value) => setView(value as ResultView)}
          data={[
            { label: sv.results.viewToggle.cards, value: "cards" },
            { label: sv.results.viewToggle.schedule, value: "schedule" },
          ]}
        />
        {latestRun && (
          <Text size="xs" c="dimmed" mt="xs" data-testid="explain-based-on">
            {sv.results.explainBasedOn(runStartedAtLabel ?? "")}
          </Text>
        )}
        {latestRunNote && (
          <Alert color="blue" mt="sm" data-testid="results-note">
            {latestRunNote}
          </Alert>
        )}
      </Card>

      {view === "cards" && planId && (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 2 }}>
            {model.sortedGroups.map((group) => {
              const members = (model.playersByGroupId.get(group.id) ?? [])
                .map((pa) => {
                  const participant = model.participantById.get(pa.participantProfileId);
                  return {
                    participantProfileId: pa.participantProfileId,
                    name: personDisplayName(persons.data, participant?.personId),
                    level: participant?.estimatedLevel ?? null,
                    source: pa.source,
                    locked: pa.locked,
                  };
                })
                .sort((a, b) => a.name.localeCompare(b.name, "sv"));
              const groupCoaches = (model.coachesByGroupId.get(group.id) ?? []).map((ca) => ({
                coachProfileId: ca.coachProfileId,
                name: model.coachNameByProfileId.get(ca.coachProfileId) ?? ca.coachProfileId,
                locked: ca.locked,
              }));
              const block = group.assignedTrainingBlockId ? model.blockById.get(group.assignedTrainingBlockId) : undefined;
              const timeBanaLabel = block ? `${block.timeSlotLabel} / ${block.courtName}` : null;
              return (
                <GroupCard
                  key={group.id}
                  planId={planId}
                  group={group}
                  timeBanaLabel={timeBanaLabel}
                  coaches={groupCoaches}
                  members={members}
                  runId={latestRunId}
                  highlightedParticipantId={highlightedParticipantId}
                  onExplain={(id, name) => setExplainTarget({ id, name })}
                  onTestMove={(id, name) => setWhatIfTarget({ id, name, currentGroupId: group.id })}
                  onExplainGroup={(id, name) => setExplainGroup({ id, name })}
                />
              );
            })}
          </SimpleGrid>
          <WaitlistCard
            entries={model.waitlist}
            runId={latestRunId}
            highlightedParticipantId={highlightedParticipantId}
            onExplain={(id, name) => setExplainTarget({ id, name })}
            onTestMove={(id, name) => setWhatIfTarget({ id, name, currentGroupId: null })}
          />
        </>
      )}

      {view === "schedule" && planId && (
        <Card withBorder padding="lg">
          <ScheduleView
            planId={planId}
            seasonPlanId={plan.data?.seasonPlanId}
            slotBlocks={trainingBlocks.data ?? []}
            groups={model.sortedGroups}
            coachNameByGroupId={model.coachNameByGroupId}
          />
        </Card>
      )}

      {planId && (
        <>
          <ExplainDrawer
            planId={planId}
            runId={latestRunId}
            participantProfileId={explainTarget?.id ?? null}
            participantName={explainTarget?.name ?? ""}
            allGroups={allGroups}
            onClose={() => setExplainTarget(null)}
            onNavigateToParticipant={(id, name) => setExplainTarget({ id, name })}
          />
          <WhatIfDialog
            planId={planId}
            runId={latestRunId}
            participantProfileId={whatIfTarget?.id ?? null}
            participantName={whatIfTarget?.name ?? ""}
            currentGroupId={whatIfTarget?.currentGroupId ?? null}
            allGroups={allGroups}
            onClose={() => setWhatIfTarget(null)}
          />
          <GroupExplainDrawer
            planId={planId}
            runId={latestRunId}
            groupId={explainGroup?.id ?? null}
            groupName={explainGroup?.name ?? ""}
            onClose={() => setExplainGroup(null)}
          />
        </>
      )}
    </Stack>
  );
}
