import { useRef, useState } from "react";
import { Alert, Badge, Button, Group, Loader, MantineColor, Select, Stack, Table, Text, Title } from "@mantine/core";
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

  // Draft state only — a row's decision is never sent to the backend until "Nästa" is clicked and
  // the whole draft is submitted as one batch (M7 review: per-change fire-and-forget saves could
  // race a fast decision -> Nästa -> Importera click, a network error, or two rapid changes into
  // committing with silently-defaulted decisions). `edits` holds only rows the user actually
  // touched; unedited rows keep the backend's own status-derived default (ImportCommitService
  // mirrors the same defaulting for anything never explicitly decided).
  const [edits, setEdits] = useState<Record<number, ImportRowDecision>>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  // Re-entrancy guard for handleNext: `setDecisions.isPending` only flips after a render, and the
  // empty-draft path never goes pending at all, so a double-click could otherwise fire onNext()
  // (or the batch PUT) twice. A ref is synchronous - the second click is a no-op immediately.
  const navigating = useRef(false);

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

  const handleDecisionChange = (rowIndex: number, decision: ImportRowDecision) => {
    setSaveError(null);
    setEdits((prev) => ({ ...prev, [rowIndex]: decision }));
  };

  const handleNext = async () => {
    if (navigating.current) {
      return;
    }
    navigating.current = true;
    if (Object.keys(edits).length === 0) {
      onNext();
      return;
    }
    setSaveError(null);
    try {
      await setDecisions.mutateAsync(edits);
      onNext();
    } catch (error) {
      if (isNotFoundError(error)) {
        onExpired();
        return;
      }
      setSaveError(error instanceof ApiError ? error.message : sv.importWizard.validate.saveDecisionFailed);
      navigating.current = false; // Save failed - stay on the step and allow a retry.
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
              const decision = edits[row.rowIndex] ?? defaultDecisionFor(row.status);
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
                        handleDecisionChange(row.rowIndex, newDecision);
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

      {saveError && <Alert color="red">{saveError}</Alert>}

      <Group justify="flex-end">
        <Button onClick={() => void handleNext()} loading={setDecisions.isPending} disabled={setDecisions.isPending}>
          {sv.importWizard.validate.nextButton}
        </Button>
      </Group>
    </Stack>
  );
}
