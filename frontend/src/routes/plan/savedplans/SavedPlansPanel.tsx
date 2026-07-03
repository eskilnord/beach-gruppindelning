import { useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, Stack, Table, Text, TextInput, Title, Tooltip } from "@mantine/core";
import { IconBookmark } from "@tabler/icons-react";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useCreateSavedPlan, useDeleteSavedPlan, useSavedPlans, useUpdateSavedPlanStatus } from "../../../api/savedPlans";
import type { SavedPlan } from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { formatDateTime } from "../../../lib/formatDateTime";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { EmptyState } from "../../../components/EmptyState";
import { canDeleteSavedPlan, statusActions, statusColor } from "./savedPlanActions";

function showError(error: unknown, fallback: string) {
  notifications.show({ color: "red", title: sv.common.error, message: error instanceof ApiError ? error.message : fallback });
}

interface FormValues {
  name: string;
}

/**
 * Sparade planer (spec §14): a save-current-state form that snapshots groups/spelare/tränare/tid/
 * bana/constraint-vikter/låsningar/score (§14.1) - every save is a brand-new immutable row, so the
 * table below is the plan's save history - plus per-row status actions following the legal
 * draft->saved->locked->published->archived transitions (§14.2/§14.3, see savedPlanActions.ts) and
 * delete for still-draft/saved rows.
 *
 * Own tab ("Planer") rather than a section inside Export (see ExportPanel.tsx): saving/locking a
 * plan is a lifecycle decision with its own history table, status actions, and cross-plan blocking
 * implications (§14.3 - locking is a hard constraint for OTHER plans' solves); export is a one-shot
 * file download. Keeping them apart matches how the spec itself separates §14 "Sparade planer" from
 * §20 "Export", and avoids burying the lifecycle actions under file-format pickers.
 */
export function SavedPlansPanel() {
  const { planId } = useParams<{ planId: string }>();
  const savedPlans = useSavedPlans(planId);
  const createSavedPlan = useCreateSavedPlan(planId ?? "");
  const updateStatus = useUpdateSavedPlanStatus(planId ?? "");
  const deleteSavedPlan = useDeleteSavedPlan(planId ?? "");

  const [deleteTarget, setDeleteTarget] = useState<SavedPlan | null>(null);

  const form = useForm<FormValues>({
    initialValues: { name: "" },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
    },
  });

  if (!planId) {
    return null;
  }

  const handleSave = form.onSubmit((values) => {
    createSavedPlan.mutate(
      { name: values.name.trim() },
      {
        onSuccess: (created) => {
          form.reset();
          notifications.show({ color: "green", message: sv.savedPlans.saveForm.saveSuccess(created.name) });
        },
        onError: (error) => showError(error, sv.savedPlans.saveForm.saveFailed),
      },
    );
  });

  const handleTransition = (savedPlanId: string, targetStatus: string) => {
    updateStatus.mutate(
      { savedPlanId, status: targetStatus },
      { onError: (error) => showError(error, sv.savedPlans.transitionFailed) },
    );
  };

  const handleDelete = () => {
    if (!deleteTarget) {
      return;
    }
    deleteSavedPlan.mutate(deleteTarget.id, {
      onSuccess: () => setDeleteTarget(null),
      onError: (error) => showError(error, sv.savedPlans.deleteModal.deleteFailed),
    });
  };

  const rows = savedPlans.data ?? [];

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={4} mb="xs">
          {sv.savedPlans.heading}
        </Title>
        <Text size="sm" c="dimmed" mb="md">
          {sv.savedPlans.description}
        </Text>

        <form onSubmit={handleSave}>
          <Group align="flex-end">
            <TextInput
              label={sv.savedPlans.saveForm.nameLabel}
              placeholder={sv.savedPlans.saveForm.namePlaceholder}
              {...form.getInputProps("name")}
            />
            <Button type="submit" loading={createSavedPlan.isPending}>
              {sv.savedPlans.saveForm.submit}
            </Button>
          </Group>
        </form>
      </Card>

      <Card withBorder padding="lg">
        {savedPlans.isLoading && <Loader size="sm" />}
        {savedPlans.isError && (
          <Alert color="red">
            {savedPlans.error instanceof ApiError ? savedPlans.error.message : sv.savedPlans.loadFailed}
          </Alert>
        )}
        {savedPlans.data && rows.length === 0 && (
          <EmptyState icon={<IconBookmark size={22} stroke={1.75} />} message={sv.savedPlans.empty} />
        )}

        {rows.length > 0 && (
          <Table.ScrollContainer minWidth={720}>
            <Table verticalSpacing="xs" withTableBorder striped highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{sv.savedPlans.columns.version}</Table.Th>
                  <Table.Th>{sv.savedPlans.columns.name}</Table.Th>
                  <Table.Th>{sv.savedPlans.columns.status}</Table.Th>
                  <Table.Th>{sv.savedPlans.columns.score}</Table.Th>
                  <Table.Th>{sv.savedPlans.columns.when}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row, index) => {
                  const actions = statusActions(row.status);
                  const statusLabel = sv.savedPlans.status[row.status as keyof typeof sv.savedPlans.status] ?? row.status;
                  return (
                    <Table.Tr key={row.id} data-testid="saved-plan-row">
                      <Table.Td>{index + 1}</Table.Td>
                      <Table.Td>{row.name}</Table.Td>
                      <Table.Td>
                        <Badge color={statusColor(row.status)}>{statusLabel}</Badge>
                      </Table.Td>
                      <Table.Td>{row.score ?? sv.savedPlans.noScore}</Table.Td>
                      <Table.Td>{formatDateTime(row.createdAt)}</Table.Td>
                      <Table.Td>
                        <Group gap="xs" justify="flex-end" wrap="nowrap">
                          {actions.map((action) => {
                            const label = sv.savedPlans.actions[action.targetStatus as keyof typeof sv.savedPlans.actions];
                            const button = (
                              <Button
                                size="xs"
                                variant="light"
                                color={action.color}
                                loading={updateStatus.isPending}
                                onClick={() => handleTransition(row.id, action.targetStatus)}
                              >
                                {label}
                              </Button>
                            );
                            return action.targetStatus === "locked" ? (
                              <Tooltip key={action.targetStatus} label={sv.savedPlans.lockTooltip} multiline w={260}>
                                {button}
                              </Tooltip>
                            ) : (
                              <span key={action.targetStatus}>{button}</span>
                            );
                          })}
                          {canDeleteSavedPlan(row.status) && (
                            <Button size="xs" variant="subtle" color="red" onClick={() => setDeleteTarget(row)}>
                              {sv.savedPlans.deleteButton}
                            </Button>
                          )}
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        )}
      </Card>

      <DeleteConfirmModal
        opened={deleteTarget !== null}
        title={sv.savedPlans.deleteModal.title}
        message={deleteTarget ? sv.savedPlans.deleteModal.message(deleteTarget.name) : ""}
        confirmLabel={sv.savedPlans.deleteModal.confirm}
        loading={deleteSavedPlan.isPending}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
      />
    </Stack>
  );
}
