import { useEffect, useState } from "react";
import { Alert, Badge, Button, Group, Loader, MantineColor, Select, Stack, Table, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { usePersons } from "../../../api/persons";
import {
  useImportValidation,
  useSetImportDecisions,
  type ImportRowDecision,
  type RowStatus,
} from "../../../api/import";
import { ApiError, isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { SessionExpiredPanel } from "../SessionExpiredPanel";

interface ValidateStepProps {
  planId: string;
  sessionId: string;
  onNext: () => void;
  onExpired: () => void;
}

const STATUS_COLOR: Record<RowStatus, MantineColor> = { OK: "green", WARN: "yellow", SKIP: "red" };

const MATCH_PREFIX = "MATCH:";

function defaultDecisionFor(status: RowStatus): ImportRowDecision {
  return status === "SKIP" ? { action: "SKIP" } : { action: "CREATE_NEW" };
}

/** Wizard step 4 (spec §8.6/§8.7): per-row status (OK/WARN/SKIP) with reasons, and a per-row decision
 *  control — create new, skip, or link to an existing person for each match proposal. Defaults
 *  mirror the backend's own commit-time defaulting (SKIP rows default to skip, everything else to
 *  create-new — never silently auto-merging into a matched person, see ImportCommitService). */
export function ValidateStep({ planId, sessionId, onNext, onExpired }: ValidateStepProps) {
  const validation = useImportValidation(planId, sessionId);
  const persons = usePersons();
  const setDecisions = useSetImportDecisions(planId, sessionId);

  const [decisions, setLocalDecisions] = useState<Record<number, ImportRowDecision>>({});

  useEffect(() => {
    if (!validation.data) {
      return;
    }
    setLocalDecisions((prev) => {
      const next = { ...prev };
      for (const row of validation.data!.rows) {
        if (!(row.rowIndex in next)) {
          next[row.rowIndex] = defaultDecisionFor(row.status);
        }
      }
      return next;
    });
  }, [validation.data]);

  if (validation.isError && isNotFoundError(validation.error)) {
    return <SessionExpiredPanel onRestart={onExpired} />;
  }
  if (validation.isError) {
    return (
      <Alert color="red">
        {validation.error instanceof ApiError ? validation.error.message : sv.common.unknownError}
      </Alert>
    );
  }
  if (validation.isLoading || !validation.data) {
    return <Loader />;
  }

  const personName = (personId: string): string => {
    const person = persons.data?.find((candidate) => candidate.id === personId);
    if (!person) {
      return sv.importWizard.validate.unknownPerson;
    }
    return person.displayName || `${person.firstName} ${person.lastName}`.trim();
  };

  const handleDecisionChange = async (rowIndex: number, decision: ImportRowDecision) => {
    setLocalDecisions((prev) => ({ ...prev, [rowIndex]: decision }));
    try {
      await setDecisions.mutateAsync({ [String(rowIndex)]: decision });
    } catch (error) {
      if (isNotFoundError(error)) {
        onExpired();
        return;
      }
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.validate.saveDecisionFailed,
      });
    }
  };

  const { okCount, warnCount, skipCount } = validation.data;

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.validate.heading}</Title>
      <Text>{sv.importWizard.validate.summary(okCount, warnCount, skipCount)}</Text>

      <Table.ScrollContainer minWidth={720}>
        <Table withTableBorder striped verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{sv.importWizard.validate.rowColumn}</Table.Th>
              <Table.Th>{sv.importWizard.validate.statusColumn}</Table.Th>
              <Table.Th>{sv.importWizard.validate.reasonsColumn}</Table.Th>
              <Table.Th>{sv.importWizard.validate.decisionColumn}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {validation.data.rows.map((row) => {
              const decision = decisions[row.rowIndex] ?? defaultDecisionFor(row.status);
              const options = [
                { value: "CREATE_NEW", label: sv.importWizard.validate.decision.createNew },
                { value: "SKIP", label: sv.importWizard.validate.decision.skip },
                ...row.matchProposals.map((proposal) => ({
                  value: `${MATCH_PREFIX}${proposal.existingPersonId}`,
                  label: sv.importWizard.validate.decision.matchExisting(
                    personName(proposal.existingPersonId),
                    sv.importWizard.validate.matchBasis[proposal.matchBasis],
                  ),
                })),
              ];
              const currentValue =
                decision.action === "MATCH_EXISTING" ? `${MATCH_PREFIX}${decision.personId}` : decision.action;

              return (
                <Table.Tr key={row.rowIndex}>
                  <Table.Td>{row.rowIndex}</Table.Td>
                  <Table.Td>
                    <Badge color={STATUS_COLOR[row.status]}>{sv.importWizard.validate.status[row.status]}</Badge>
                  </Table.Td>
                  <Table.Td>
                    {row.reasons.length > 0 ? (
                      <Stack gap={2}>
                        {row.reasons.map((reason, index) => (
                          <Text size="sm" key={index}>
                            {reason}
                          </Text>
                        ))}
                      </Stack>
                    ) : (
                      <Text size="sm" c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Select
                      aria-label={`Beslut för rad ${row.rowIndex}`}
                      data={options}
                      value={currentValue}
                      onChange={(value) => {
                        if (!value) {
                          return;
                        }
                        const newDecision: ImportRowDecision = value.startsWith(MATCH_PREFIX)
                          ? { action: "MATCH_EXISTING", personId: value.slice(MATCH_PREFIX.length) }
                          : { action: value as "CREATE_NEW" | "SKIP" };
                        void handleDecisionChange(row.rowIndex, newDecision);
                      }}
                      w={340}
                      comboboxProps={{ withinPortal: false }}
                    />
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      <Group justify="flex-end">
        <Button onClick={onNext}>{sv.importWizard.validate.nextButton}</Button>
      </Group>
    </Stack>
  );
}
