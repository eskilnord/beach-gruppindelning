import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Group } from "@mantine/core";
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData } from "@mantine/spotlight";
import { useAssignments } from "../../api/assignments";
import { useGroups } from "../../api/groups";
import { useParticipants } from "../../api/participants";
import { usePersons } from "../../api/persons";
import type { Person } from "../../api/types";
import { sv } from "../../i18n/sv";
import { matchesSearchQuery } from "./participantSearch";

interface PlayerSearchSpotlightProps {
  planId: string;
}

function personDisplayName(persons: Person[] | undefined, personId: string): string {
  const person = persons?.find((candidate) => candidate.id === personId);
  if (!person) {
    return personId;
  }
  return person.displayName || `${person.firstName} ${person.lastName}`.trim();
}

/**
 * Global Ctrl/Cmd+F player lookup, scoped to the ACTIVE plan's participants. Mounted by
 * AppShellLayout only while `planId` is present (a plan is open) - "Spotlight only registers when a
 * plan is active" - so the `mod+F` hotkey never hijacks the browser's real find on the Startvy/
 * Säsongsvy, where there is nothing plan-scoped to search.
 *
 * Selecting a hit either jumps to Resultatvy and flash-highlights the player's row
 * (ResultsPanel.tsx reads `?highlight=<id>`) once the plan has been solved at least once (has
 * groups), or falls back to opening the participant's detail drawer in Deltagarvy
 * (ParticipantsPanel.tsx reads `?participant=<id>`) when it hasn't - matching how GroupCard/
 * WaitlistCard's own explain/what-if actions already gate on "has this plan produced groups yet".
 */
export function PlayerSearchSpotlight({ planId }: PlayerSearchSpotlightProps) {
  const navigate = useNavigate();
  const participants = useParticipants(planId);
  const persons = usePersons();
  const groups = useGroups(planId);
  const assignments = useAssignments(planId);

  const hasGroups = (groups.data?.length ?? 0) > 0;

  const groupNameById = useMemo(() => new Map((groups.data ?? []).map((g) => [g.id, g.name])), [groups.data]);

  const groupIdByParticipantId = useMemo(() => {
    const map = new Map<string, string | null>();
    for (const player of assignments.data?.players ?? []) {
      map.set(player.participantProfileId, player.groupId ?? null);
    }
    return map;
  }, [assignments.data]);

  const actions: SpotlightActionData[] = useMemo(
    () =>
      (participants.data ?? []).map((participant) => {
        const name = personDisplayName(persons.data, participant.personId);
        const groupId = groupIdByParticipantId.get(participant.id) ?? null;
        const groupName = groupId ? (groupNameById.get(groupId) ?? null) : null;
        const isResultsWaitlisted = hasGroups && groupId === null;

        return {
          id: participant.id,
          label: name,
          description: groupName ?? undefined,
          rightSection: (
            <Group gap={4} wrap="nowrap">
              {isResultsWaitlisted && (
                <Badge size="xs" color="yellow" variant="light">
                  {sv.playerSearch.resultsWaitlistBadge}
                </Badge>
              )}
              {participant.estimatedLevel != null && (
                <Badge size="xs" variant="light">
                  {sv.playerSearch.levelBadge(Math.round(participant.estimatedLevel))}
                </Badge>
              )}
              {participant.waitlisted && (
                <Badge size="xs" color="yellow" variant="outline">
                  {sv.participants.waitlistedBadge}
                </Badge>
              )}
              {participant.manualReviewFlag && (
                <Badge size="xs" color="red" variant="outline">
                  {sv.participants.columns.needsReview}
                </Badge>
              )}
            </Group>
          ),
          onClick: () => {
            if (hasGroups) {
              navigate(`/plans/${planId}/resultat?highlight=${participant.id}`);
            } else {
              navigate(`/plans/${planId}/deltagare?participant=${participant.id}`);
            }
          },
        } satisfies SpotlightActionData;
      }),
    [participants.data, persons.data, groupIdByParticipantId, groupNameById, hasGroups, navigate, planId],
  );

  const filter = (
    query: string,
    items: (SpotlightActionData | SpotlightActionGroupData)[],
  ): (SpotlightActionData | SpotlightActionGroupData)[] =>
    items.filter((item) => "label" in item && matchesSearchQuery(item.label ?? "", query));

  return (
    <Spotlight
      actions={actions}
      filter={filter}
      shortcut="mod + F"
      tagsToIgnore={[]}
      limit={20}
      nothingFound={sv.playerSearch.nothingFound}
      searchProps={{ placeholder: sv.playerSearch.placeholder }}
      data-testid="player-search-spotlight"
    />
  );
}
