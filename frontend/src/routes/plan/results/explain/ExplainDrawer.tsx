import { useState } from "react";
import { Accordion, Alert, Badge, Button, Divider, Drawer, Group, Loader, Select, Stack, Table, Text, Title } from "@mantine/core";
import { usePersonExplanation } from "../../../../api/explanations";
import { useWhatIfWhyNot } from "../../../../api/whatif";
import { ApiError } from "../../../../api/client";
import type { BrokenWishView } from "../../../../api/types";
import { sv } from "../../../../i18n/sv";
import { AlternativeCard } from "./AlternativeCard";
import { weightBadgeLabel } from "./badges";
import { describeStaleness } from "./staleness";

export interface GroupOption {
  id: string;
  name: string;
}

interface ExplainDrawerProps {
  planId: string;
  /** The latest run's id - undefined when the plan has never been solved (drawer never opens then,
   *  see GroupCard/WaitlistCard's disabled-button guard). */
  runId: string | undefined;
  participantProfileId: string | null;
  /** Shown as a placeholder title while the explanation is loading. */
  participantName: string;
  allGroups: GroupOption[];
  onClose: () => void;
  /** Jump the drawer to a different participant - used by the "waitlisted friend" link (a broken
   *  pair-wish whose other side is themselves unplaced, backend amendment (c)). */
  onNavigateToParticipant: (participantProfileId: string, name: string) => void;
}

/**
 * The M7 explain drawer (kravspec §17.1/§17.2/§17.3, "Personnivå" of the Förklarbarhet chapter):
 * vald grupp / positiva+negativa faktorer / brutna önskemål / tillämpade vikter / ALTERNATIVEN
 * comparison cards + an ad-hoc "Varför inte...?" group picker, for a placed member. Renders the
 * WAITLIST narrative instead (reasonSv + per-group hard blockers + "förbättring möjlig" callout)
 * when the participant has no group (`response.selectedGroup` is null).
 */
export function ExplainDrawer({
  planId,
  runId,
  participantProfileId,
  participantName,
  allGroups,
  onClose,
  onNavigateToParticipant,
}: ExplainDrawerProps) {
  const opened = participantProfileId !== null;
  const explanation = usePersonExplanation(planId, runId, participantProfileId ?? undefined);
  const title = explanation.data ? sv.results.explain.title(explanation.data.name) : sv.results.explain.title(participantName);

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="xl" title={title} data-testid="explain-drawer">
      {opened && (
        <ExplainDrawerBody
          key={`${runId ?? ""}:${participantProfileId}`}
          planId={planId}
          runId={runId}
          participantProfileId={participantProfileId}
          allGroups={allGroups}
          onNavigateToParticipant={onNavigateToParticipant}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

interface ExplainDrawerBodyProps {
  planId: string;
  runId: string | undefined;
  participantProfileId: string;
  allGroups: GroupOption[];
  onNavigateToParticipant: (participantProfileId: string, name: string) => void;
  onClose: () => void;
}

function ExplainDrawerBody({
  planId,
  runId,
  participantProfileId,
  allGroups,
  onNavigateToParticipant,
  onClose,
}: ExplainDrawerBodyProps) {
  const explanation = usePersonExplanation(planId, runId, participantProfileId);
  const whyNot = useWhatIfWhyNot(planId);
  const [whyNotGroupId, setWhyNotGroupId] = useState<string | null>(null);

  if (explanation.isLoading) {
    return <Loader size="sm" />;
  }
  if (explanation.isError || !explanation.data) {
    return (
      <Alert color="red">
        {explanation.error instanceof ApiError ? explanation.error.message : sv.results.explain.loadFailed}
      </Alert>
    );
  }

  const data = explanation.data;
  const banner = describeStaleness(data.stale);
  const brokenMessages = new Set(data.brokenWishes.map((w) => w.messageSv));
  const genericNegative = data.negativeFactors.filter((f) => !brokenMessages.has(f.messageSv));
  const weightByKey = new Map(data.appliedWeights.map((w) => [w.key, w]));

  const groupOptions = allGroups
    .filter((g) => g.id !== data.selectedGroup?.groupId)
    .map((g) => ({ value: g.id, label: g.name }));

  const handleWhyNot = () => {
    if (!runId || !whyNotGroupId) {
      return;
    }
    whyNot.mutate({ participantProfileId, groupId: whyNotGroupId, runId });
  };

  return (
    <Stack gap="md">
      {banner.show && (
        <Alert color="yellow" data-testid="explain-stale-banner">
          {banner.message}
        </Alert>
      )}

      {data.selectedGroup ? (
        <div data-testid="explain-selected-group">
          <Title order={5}>{sv.results.explain.selectedGroupHeading}</Title>
          <Text fw={600}>{data.selectedGroup.name}</Text>
          <Text size="sm" c="dimmed">
            {sv.results.groupCard.playersCount(data.selectedGroup.size, data.selectedGroup.targetSize ?? null, data.selectedGroup.maxSize ?? null)}
          </Text>
          {data.selectedGroup.levelMeanSv && (
            <Text size="sm" c="dimmed">
              {sv.results.groupCard.levelMean}: {data.selectedGroup.levelMeanSv}
              {data.selectedGroup.levelSpread != null ? ` · ${sv.results.groupCard.levelSpread}: ${data.selectedGroup.levelSpread}` : ""}
            </Text>
          )}
        </div>
      ) : (
        data.waitlist && (
          <div data-testid="explain-waitlist-narrative">
            <Title order={5}>{sv.results.waitlist.heading}</Title>
            <Text size="sm">{data.waitlist.reasonSv}</Text>
            {data.waitlist.qualityWarningSv && (
              <Alert color="blue" title={sv.results.explain.waitlist.qualityWarningTitle} mt="xs">
                {data.waitlist.qualityWarningSv}
              </Alert>
            )}
            {data.waitlist.perGroupBlockers.length > 0 && (
              <>
                <Text size="sm" fw={500} mt="sm">
                  {sv.results.explain.waitlist.blockersHeading}
                </Text>
                <Table verticalSpacing={4} withTableBorder mt={4}>
                  <Table.Tbody>
                    {data.waitlist.perGroupBlockers.map((blocker) => (
                      <Table.Tr key={blocker.groupId}>
                        <Table.Td>{blocker.name}</Table.Td>
                        <Table.Td>{blocker.blockerSv}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </>
            )}
          </div>
        )
      )}

      <Divider />

      <div data-testid="explain-positive-factors">
        <Title order={5}>{sv.results.explain.positiveHeading}</Title>
        {data.positiveFactors.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.explain.noPositive}
          </Text>
        )}
        {data.positiveFactors.map((f, i) => (
          <Text key={i} size="sm" c="green" data-testid="explain-positive-factor">
            ✓ {f.messageSv}
          </Text>
        ))}
      </div>

      <div data-testid="explain-negative-factors">
        <Title order={5}>{sv.results.explain.negativeHeading}</Title>
        {genericNegative.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.explain.noNegative}
          </Text>
        )}
        {genericNegative.map((f, i) => (
          <Text key={i} size="sm" c="orange" data-testid="explain-negative-factor">
            ✗ {f.messageSv}
          </Text>
        ))}
      </div>

      <div data-testid="explain-broken-wishes">
        <Title order={5}>{sv.results.explain.brokenWishesHeading}</Title>
        {data.brokenWishes.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.explain.noBrokenWishes}
          </Text>
        )}
        {data.brokenWishes.map((wish, i) => (
          <BrokenWishRow
            key={i}
            wish={wish}
            weightLabel={
              wish.weightApplied > 0 && weightByKey.get(wish.key)
                ? weightBadgeLabel(weightByKey.get(wish.key)!.level, wish.weightApplied)
                : null
            }
            onNavigateToParticipant={onNavigateToParticipant}
          />
        ))}
      </div>

      <Divider />

      <Accordion variant="separated">
        <Accordion.Item value="weights">
          <Accordion.Control>{sv.results.explain.appliedWeightsHeading}</Accordion.Control>
          <Accordion.Panel>
            <Table verticalSpacing={4} withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{sv.results.explain.appliedWeightsTable.label}</Table.Th>
                  <Table.Th>{sv.results.explain.appliedWeightsTable.level}</Table.Th>
                  <Table.Th>{sv.results.explain.appliedWeightsTable.weight}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {data.appliedWeights.map((w) => (
                  <Table.Tr key={w.key}>
                    <Table.Td>{w.label}</Table.Td>
                    <Table.Td>{sv.hardOrSoft[w.level as keyof typeof sv.hardOrSoft] ?? w.level}</Table.Td>
                    <Table.Td>{weightBadgeLabel(w.level, w.weight)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>

      <Divider />

      <div data-testid="explain-alternatives">
        <Title order={5}>{sv.results.explain.alternativesHeading}</Title>
        {data.alternatives.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.explain.noAlternatives}
          </Text>
        )}
        <Stack gap="xs" mt="xs">
          {data.alternatives.map((alt) => (
            <AlternativeCard key={alt.groupId} alternative={alt} />
          ))}
        </Stack>
      </div>

      <Divider />

      <div data-testid="explain-why-not">
        <Group mt="xs" align="flex-end">
          <Select
            label={sv.results.explain.whyNotHeading}
            data={groupOptions}
            placeholder={sv.results.explain.whyNotPlaceholder}
            value={whyNotGroupId}
            onChange={setWhyNotGroupId}
            comboboxProps={{ withinPortal: false }}
            style={{ flex: 1 }}
          />
          <Button onClick={handleWhyNot} loading={whyNot.isPending} disabled={!whyNotGroupId}>
            {sv.results.explain.whyNotButton}
          </Button>
        </Group>
        {whyNot.isError && (
          <Alert color="red" mt="xs">
            {whyNot.error instanceof ApiError ? whyNot.error.message : sv.results.explain.whyNotFailed}
          </Alert>
        )}
        {whyNot.data && (
          <Stack gap="xs" mt="xs">
            <AlternativeCard alternative={whyNot.data.alternative} />
          </Stack>
        )}
      </div>

      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {sv.common.close}
        </Button>
      </Group>
    </Stack>
  );
}

interface BrokenWishRowProps {
  wish: BrokenWishView;
  weightLabel: string | null;
  onNavigateToParticipant: (participantProfileId: string, name: string) => void;
}

function BrokenWishRow({ wish, weightLabel, onNavigateToParticipant }: BrokenWishRowProps) {
  return (
    <Group gap={6} wrap="wrap" mb={2}>
      <Text size="sm" c="red">
        ✗ {wish.messageSv}
      </Text>
      {weightLabel && (
        <Badge size="xs" color="red" variant="light">
          {weightLabel}
        </Badge>
      )}
      {wish.unassignedFriendParticipantProfileId && (
        <Button
          size="compact-xs"
          variant="subtle"
          onClick={() => onNavigateToParticipant(wish.unassignedFriendParticipantProfileId!, wish.withPerson ?? "")}
        >
          {sv.results.explain.waitlistedFriendLink(wish.withPerson ?? "")}
        </Button>
      )}
    </Group>
  );
}
