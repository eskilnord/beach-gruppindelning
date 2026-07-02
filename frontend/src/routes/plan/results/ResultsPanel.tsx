import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Card, Loader, SegmentedControl, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { useAssignments } from "../../../api/assignments";
import { ApiError } from "../../../api/client";
import { useCoaches } from "../../../api/coaches";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useGroups } from "../../../api/groups";
import { useParticipantFieldValues, useParticipants } from "../../../api/participants";
import { usePersons } from "../../../api/persons";
import { usePlan } from "../../../api/plans";
import { useTrainingBlocksForPlan } from "../../../api/trainingBlocks";
import { sv } from "../../../i18n/sv";
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

  const plan = usePlan(planId);
  const groups = useGroups(planId);
  const assignments = useAssignments(planId);
  const participants = useParticipants(planId);
  const persons = usePersons();
  const coaches = useCoaches(planId);
  const trainingBlocks = useTrainingBlocksForPlan(planId);
  const fieldDefinitions = useFieldDefinitions(planId);

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
        <Text c="dimmed" mb="md">
          {sv.results.empty}
        </Text>
        <Button variant="default" onClick={() => navigate(`/plans/${planId}/optimering`)}>
          {sv.plan.tabs.optimize}
        </Button>
      </Card>
    );
  }

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
      </Card>

      {view === "cards" && planId && (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
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
                />
              );
            })}
          </SimpleGrid>
          <WaitlistCard entries={model.waitlist} />
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
    </Stack>
  );
}
