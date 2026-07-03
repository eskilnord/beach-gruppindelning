import { useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Button, Card, Group, Loader, Table, Tabs, Title } from "@mantine/core";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import { FieldRow } from "./FieldRow";
import { NewFieldModal } from "./NewFieldModal";
import { ConstraintWeightsTable } from "./ConstraintWeightsTable";

/**
 * Fältbyggare tab (spec §19.5, replaces the M2 placeholder): a table of every field visible to the
 * plan (standard + custom) with the optimization-facing columns editable inline, a "Nytt fält" modal
 * for creating plan-scoped custom fields, and a "Konfiguration" sub-tab for the 24 standard
 * constraint weights (spec §9.4/§7.16).
 */
export function FieldsPanel() {
  const { planId } = useParams<{ planId: string }>();
  const fieldDefinitions = useFieldDefinitions(planId);
  const [newFieldOpen, setNewFieldOpen] = useState(false);

  return (
    <Card withBorder padding="lg">
      <Tabs defaultValue="fields" keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="fields">{sv.fieldBuilder.tabs.fields}</Tabs.Tab>
          <Tabs.Tab value="configuration">{sv.fieldBuilder.tabs.configuration}</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="fields">
          <Group justify="space-between" mb="sm">
            <Group gap={4}>
              <Title order={4}>{sv.fieldBuilder.heading}</Title>
              <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.heading)}>{sv.help.fields.whatIsAField}</HelpTip>
            </Group>
            <Button onClick={() => setNewFieldOpen(true)}>{sv.fieldBuilder.newFieldButton}</Button>
          </Group>

          {fieldDefinitions.isLoading && <Loader size="sm" />}
          {fieldDefinitions.isError && (
            <Alert color="red">
              {fieldDefinitions.error instanceof ApiError ? fieldDefinitions.error.message : sv.fieldBuilder.loadFailed}
            </Alert>
          )}
          {fieldDefinitions.data && (
            <Table.ScrollContainer minWidth={960}>
              <Table verticalSpacing="xs" withTableBorder striped highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{sv.fieldBuilder.table.label}</Table.Th>
                    <Table.Th>{sv.fieldBuilder.table.type}</Table.Th>
                    <Table.Th>
                      <Group gap={4} wrap="nowrap">
                        {sv.fieldBuilder.table.affectsOptimization}
                        <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.table.affectsOptimization)}>
                          {sv.help.fields.affectsOptimization}
                        </HelpTip>
                      </Group>
                    </Table.Th>
                    <Table.Th>
                      <Group gap={4} wrap="nowrap">
                        {sv.fieldBuilder.table.constraint}
                        <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.table.constraint)}>
                          {sv.help.fields.constraintType}
                        </HelpTip>
                      </Group>
                    </Table.Th>
                    <Table.Th>
                      <Group gap={4} wrap="nowrap">
                        {sv.fieldBuilder.table.hardOrSoft}
                        <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.table.hardOrSoft)}>
                          {sv.help.fields.hardOrSoft}
                        </HelpTip>
                      </Group>
                    </Table.Th>
                    <Table.Th>
                      <Group gap={4} wrap="nowrap">
                        {sv.fieldBuilder.table.weight}
                        <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.table.weight)}>
                          {sv.help.fields.weightInTable}
                        </HelpTip>
                      </Group>
                    </Table.Th>
                    <Table.Th>
                      <Group gap={4} wrap="nowrap">
                        {sv.fieldBuilder.table.explanation}
                        <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.table.explanation)}>
                          {sv.help.fields.explanation}
                        </HelpTip>
                      </Group>
                    </Table.Th>
                    <Table.Th />
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {fieldDefinitions.data.map((field) => (
                    <FieldRow key={field.id} field={field} planId={planId!} />
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          )}
        </Tabs.Panel>

        <Tabs.Panel value="configuration">{planId && <ConstraintWeightsTable planId={planId} />}</Tabs.Panel>
      </Tabs>

      {planId && <NewFieldModal planId={planId} opened={newFieldOpen} onClose={() => setNewFieldOpen(false)} />}
    </Card>
  );
}
