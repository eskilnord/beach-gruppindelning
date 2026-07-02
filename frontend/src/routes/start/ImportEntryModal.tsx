import { useState } from "react";
import { Alert, Button, Group, Modal, Select, Stack, Text } from "@mantine/core";
import { useSeasons } from "../../api/seasons";
import { usePlansForSeason } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import { CreatePlanModal } from "../season/CreatePlanModal";

interface ImportEntryModalProps {
  opened: boolean;
  onClose: () => void;
  onContinue: (planId: string) => void;
}

/** Startvy's "Importera ny fil" entry point (spec §19.1): picks a season and an activity plan (or
 *  creates a new one) before handing off to the import wizard at `/plans/:planId/import`. */
export function ImportEntryModal({ opened, onClose, onContinue }: ImportEntryModalProps) {
  const [seasonId, setSeasonId] = useState<string | null>(null);
  const [planId, setPlanId] = useState<string | null>(null);
  const [createPlanOpen, setCreatePlanOpen] = useState(false);

  const seasons = useSeasons();
  const plans = usePlansForSeason(seasonId ?? undefined);

  const handleClose = () => {
    setSeasonId(null);
    setPlanId(null);
    onClose();
  };

  const handleContinue = () => {
    if (!planId) {
      return;
    }
    onContinue(planId);
    handleClose();
  };

  return (
    <>
      <Modal opened={opened} onClose={handleClose} title={sv.importEntry.title} centered>
        <Stack gap="sm">
          {seasons.isError && (
            <Alert color="red">
              {seasons.error instanceof ApiError ? seasons.error.message : sv.common.unknownError}
            </Alert>
          )}
          {seasons.data && seasons.data.length === 0 ? (
            <Text c="dimmed">{sv.importEntry.noSeasons}</Text>
          ) : (
            <Select
              label={sv.importEntry.seasonLabel}
              placeholder={sv.importEntry.seasonPlaceholder}
              data={(seasons.data ?? []).map((season) => ({ value: season.id, label: season.name }))}
              value={seasonId}
              onChange={(value) => {
                setSeasonId(value);
                setPlanId(null);
              }}
              data-autofocus
            />
          )}

          {seasonId && (
            <>
              {plans.data && plans.data.length === 0 ? (
                <Text c="dimmed">{sv.importEntry.noPlans}</Text>
              ) : (
                <Select
                  label={sv.importEntry.planLabel}
                  placeholder={sv.importEntry.planPlaceholder}
                  data={(plans.data ?? []).map((plan) => ({ value: plan.id, label: plan.name }))}
                  value={planId}
                  onChange={setPlanId}
                />
              )}
              <Button variant="subtle" onClick={() => setCreatePlanOpen(true)} w="fit-content">
                {sv.importEntry.createPlanButton}
              </Button>
            </>
          )}

          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button onClick={handleContinue} disabled={!planId}>
              {sv.importEntry.continueButton}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {seasonId && (
        <CreatePlanModal
          opened={createPlanOpen}
          seasonId={seasonId}
          onClose={() => setCreatePlanOpen(false)}
          onCreated={(newPlanId) => {
            setCreatePlanOpen(false);
            setPlanId(newPlanId);
          }}
        />
      )}
    </>
  );
}
