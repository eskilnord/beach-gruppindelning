import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Accordion, Alert, Button, Card, Group, Loader, Stack, Text, Title } from "@mantine/core";
import { useDebouncedCallback } from "@mantine/hooks";
import { IconAlertTriangle, IconInfoCircle } from "@tabler/icons-react";
import { ApiError } from "../../../api/client";
import {
  priorityOrderKey,
  type PriorityKey,
  type PriorityOrderView,
  type PriorityRowView,
  usePriorityOrder,
  useSetPriorityOrder,
} from "../../../api/priorityOrder";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { sv } from "../../../i18n/sv";
import { useUiMode } from "../../../lib/uiMode/useUiMode";
import { PriorityRankList } from "./PriorityRankList";
import { isPermutation, moveItem, reorder } from "./priorityOrder";

type SaveStatus = { kind: "idle" } | { kind: "saving" } | { kind: "saved" } | { kind: "error"; message: string };

/**
 * v0.6.0 F3 (M-S3): the real "Vad är viktigast?" Prioriteringar screen (replaces the F2 placeholder
 * card) - a drag/arrow-reorderable ranking of the four priority buckets, autosaved (no save button)
 * via a debounced `PUT /api/plans/{planId}/priority-order`, plus the two states that make the
 * ordering's actual effect on optimization honest: `staleSinceLastRun` (order changed since the last
 * solve) and `customWeightsActive` (advanced-mode weight edits have moved the plan off any coherent
 * priority-order ladder, so this list is currently advisory-only).
 *
 * Reachable in both SIMPLE and ADVANCED mode (router.tsx: no <AdvancedRouteGate>, same as F2) - the
 * same interactive panel renders in both.
 */
export function PrioritiesPanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { setMode } = useUiMode();

  const priorityOrder = usePriorityOrder(planId);
  const setPriorityOrder = useSetPriorityOrder(planId ?? "");

  const [localOrder, setLocalOrder] = useState<PriorityKey[] | null>(null);
  // v0.6.0 F3 review fix (FIX 3, BLOCKER, local-state sync policy): true from the moment a local
  // edit is committed (commitOrder) until it's either confirmed saved or reverted by a failed save -
  // gates the resync effect below so a background refetch never clobbers an outstanding edit, while
  // still letting the list pick up genuine server-side changes (another tab, the reset flow, an
  // advanced-mode weight edit re-inferring the order) the moment there's nothing pending locally.
  const [dirty, setDirty] = useState(false);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>({ kind: "idle" });
  const [resetOpen, setResetOpen] = useState(false);
  const [resetError, setResetError] = useState<string | null>(null);

  // The last order the backend actually confirmed (via GET or a successful PUT) - what a failed PUT
  // reverts back to.
  const lastConfirmedOrderRef = useRef<PriorityKey[] | null>(null);
  // The order a failed PUT was actually trying to save - "Försök igen" retries exactly this, so the
  // admin doesn't have to redo the drag/arrow interaction that failed.
  const lastAttemptedOrderRef = useRef<PriorityKey[] | null>(null);
  // v0.6.0 F3 review fix (FIX 2, BLOCKER, PUT sequencing): bumped on every save attempt (autosave OR
  // reset) - mirrors src/lib/uiMode/useUiMode.ts's B4 requestToken pattern. An attempt's onSuccess/
  // onError only apply their cache write / status change / revert when this ref still equals the
  // token captured at the moment that attempt was fired - an older response landing after a newer
  // attempt has since been made is silently dropped instead of clobbering the cache or flipping the
  // status back.
  const saveTokenRef = useRef(0);
  // v0.6.0 F3 review fix (FIX 3): tracks which planId the state above belongs to, so a planId change
  // (the admin navigating from one plan's Prioriteringar screen straight to another's, same mounted
  // route) resets everything instead of momentarily showing/saving the PREVIOUS plan's order.
  const currentPlanIdRef = useRef(planId);

  const saveOrder = (newOrder: PriorityKey[]) => {
    if (!planId) {
      return;
    }
    lastAttemptedOrderRef.current = newOrder;
    const token = ++saveTokenRef.current;
    setSaveStatus({ kind: "saving" });
    setPriorityOrder.mutate(newOrder, {
      onSuccess: (data) => {
        if (saveTokenRef.current !== token) {
          return; // a newer save attempt has since started - this response is stale, drop it.
        }
        queryClient.setQueryData(priorityOrderKey(planId), data);
        lastConfirmedOrderRef.current = data.order;
        setDirty(false);
        setSaveStatus({ kind: "saved" });
      },
      onError: (error) => {
        if (saveTokenRef.current !== token) {
          return;
        }
        // Optimistic edit reverts (F3 hard requirement) - the admin sees the list snap back to the
        // last confirmed order alongside the error, rather than being left on a value that never
        // actually saved.
        setLocalOrder(lastConfirmedOrderRef.current);
        setDirty(false);
        setSaveStatus({
          kind: "error",
          message: error instanceof ApiError ? error.message : sv.simple.priorities.saveFailed,
        });
      },
    });
  };

  const debouncedSave = useDebouncedCallback(saveOrder, { delay: 600, flushOnUnmount: true });

  const commitOrder = (newOrder: PriorityKey[]) => {
    if (!isPermutation(newOrder, lastConfirmedOrderRef.current ?? newOrder)) {
      return; // defensive - moveItem/reorder never actually produce this, see priorityOrder.ts.
    }
    setLocalOrder(newOrder);
    setDirty(true);
    // Shown immediately (not only once the debounce fires) so the admin gets instant "this is
    // saving" feedback on every keypress/click, matching the optimistic-order update.
    setSaveStatus({ kind: "saving" });
    debouncedSave(newOrder);
  };

  useEffect(() => {
    if (currentPlanIdRef.current === planId) {
      return;
    }
    currentPlanIdRef.current = planId;
    // A pending (not-yet-fired) debounced save belongs to the OLD planId's edit - the component
    // doesn't unmount on a route-param-only navigation (same route element, new :planId), so
    // useDebouncedCallback's own unmount-only flush (FIX 1) never runs here. Cancel it outright
    // (never flush it) - firing it now would PUT the old plan's edited order at the NEW plan's
    // endpoint (`setPriorityOrder`/`debouncedSave` both close over the latest render's `planId` by
    // the time the timer would fire, per mantine's useCallbackRef), silently corrupting the new
    // plan's saved order with unrelated data.
    debouncedSave.cancel();
    saveTokenRef.current += 1; // invalidate any still-in-flight save tied to the old planId
    setLocalOrder(null);
    setDirty(false);
    setSaveStatus({ kind: "idle" });
    setResetOpen(false);
    setResetError(null);
    lastConfirmedOrderRef.current = null;
    lastAttemptedOrderRef.current = null;
  }, [planId, debouncedSave]);

  // Re-syncs `localOrder` from the query's data whenever that data changes (initial load, background
  // refetch, another tab's edit, the FIX 8 cross-invalidation from a constraint-weights PUT, ...) -
  // but ONLY while there's no pending local edit (`dirty`). A dirty local edit is never silently
  // overwritten by a refetch; it's resolved by that edit's own save succeeding (which also updates
  // `lastConfirmedOrderRef` directly) or failing (which reverts and clears `dirty`, letting this
  // effect take back over on the next data change).
  useEffect(() => {
    if (!priorityOrder.data || dirty) {
      return;
    }
    setLocalOrder(priorityOrder.data.order);
    lastConfirmedOrderRef.current = priorityOrder.data.order;
  }, [priorityOrder.data, dirty]);

  const handleMove = (index: number, direction: "up" | "down") => {
    if (!localOrder) {
      return;
    }
    const next = moveItem(localOrder, index, direction);
    if (next !== localOrder) {
      commitOrder(next);
    }
  };

  const handleReorder = (fromIndex: number, toIndex: number) => {
    if (!localOrder) {
      return;
    }
    const next = reorder(localOrder, fromIndex, toIndex);
    if (next !== localOrder) {
      commitOrder(next);
    }
  };

  const handleRetry = () => {
    if (lastAttemptedOrderRef.current) {
      setLocalOrder(lastAttemptedOrderRef.current);
      setDirty(true);
      saveOrder(lastAttemptedOrderRef.current);
    }
  };

  const handleOpenAdvanced = () => {
    setMode("ADVANCED");
    // No falt sub-tab deep-link mechanism exists (FieldsPanel.tsx's Tabs are plain uncontrolled
    // state, no URL param) - land on the tab itself, same as any other advanced-only deep link.
    navigate(`/plans/${planId}/falt`);
  };

  if (!planId) {
    return null;
  }

  const data: PriorityOrderView | undefined = priorityOrder.data;
  const displayOrder = localOrder ?? data?.order ?? [];
  const priorities: PriorityRowView[] = data?.priorities ?? [];

  const handleResetConfirm = () => {
    if (!planId) {
      return;
    }
    setResetError(null);
    // v0.6.0 F3 review fix (FIX 3, BLOCKER): PUTs `displayOrder` - the order actually shown and
    // implicitly confirmed by the admin right now - not the raw `priorityOrder.data.order`. Those
    // two can differ whenever there's a pending local edit still in flight when "Återställ" is
    // clicked; sending `data.order` in that case would silently discard the visible edit.
    const orderToSave = displayOrder;
    setDirty(true);
    const token = ++saveTokenRef.current;
    setPriorityOrder.mutate(orderToSave, {
      onSuccess: (resultData) => {
        if (saveTokenRef.current !== token) {
          return;
        }
        queryClient.setQueryData(priorityOrderKey(planId), resultData);
        lastConfirmedOrderRef.current = resultData.order;
        setLocalOrder(resultData.order);
        setDirty(false);
        setResetOpen(false);
      },
      onError: (error) => {
        if (saveTokenRef.current !== token) {
          return;
        }
        setDirty(false);
        setResetError(error instanceof ApiError ? error.message : sv.simple.priorities.resetFailed);
      },
    });
  };

  // v0.6.0 F3 review fix (FIX 10, MINOR): the "Så här tolkas ordningen" accordion is sorted by the
  // currently DISPLAYED order (what the admin sees in the ranked list above), not `priorities`'s raw
  // server array order (which is rank-stable from the last GET/PUT and can visibly diverge from
  // `displayOrder` while a local reorder is pending save).
  const priorityByKey = new Map(priorities.map((row) => [row.key, row]));
  const orderedPriorities = displayOrder
    .map((key) => priorityByKey.get(key))
    .filter((row): row is PriorityRowView => row !== undefined);

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Group justify="space-between" align="flex-start" mb={4}>
          <Title order={3}>{sv.simple.priorities.heading}</Title>
          <div data-testid="priority-save-status" role="status" aria-live="polite">
            {saveStatus.kind === "saving" && (
              <Text c="dimmed" size="sm">
                {sv.simple.priorities.saving}
              </Text>
            )}
            {saveStatus.kind === "saved" && (
              <Text c="green" size="sm">
                {sv.simple.priorities.saved}
              </Text>
            )}
            {saveStatus.kind === "error" && (
              <Alert color="red" p="xs" icon={<IconAlertTriangle size={16} />}>
                <Stack gap={4}>
                  <Text size="sm">{saveStatus.message}</Text>
                  <Button size="xs" variant="light" color="red" onClick={handleRetry}>
                    {sv.simple.priorities.retryButton}
                  </Button>
                </Stack>
              </Alert>
            )}
          </div>
        </Group>
        <Text c="dimmed" size="sm" mb="md">
          {sv.simple.priorities.intro}
        </Text>

        {priorityOrder.isLoading && <Loader size="sm" />}
        {priorityOrder.isError && !data && (
          <Alert color="red">
            {priorityOrder.error instanceof ApiError ? priorityOrder.error.message : sv.simple.priorities.loadFailed}
          </Alert>
        )}

        {data && (
          <>
            {data.staleSinceLastRun && (
              <Alert color="blue" mb="sm" icon={<IconInfoCircle size={16} />} data-testid="priority-stale-alert">
                <Stack gap={8}>
                  <Text size="sm">{sv.simple.priorities.staleAlert.message}</Text>
                  <Button
                    size="xs"
                    variant="light"
                    onClick={() => navigate(`/plans/${planId}/optimering`)}
                    style={{ alignSelf: "flex-start" }}
                  >
                    {sv.simple.priorities.staleAlert.button}
                  </Button>
                </Stack>
              </Alert>
            )}

            {data.customWeightsActive && (
              <Alert color="yellow" mb="sm" title={sv.simple.priorities.overridesAlert.title} data-testid="priority-overrides-alert">
                <Stack gap={8}>
                  <Text size="sm">{sv.simple.priorities.overridesAlert.body}</Text>
                  <Group gap="xs">
                    <Button size="xs" variant="light" onClick={handleOpenAdvanced}>
                      {sv.simple.priorities.overridesAlert.openAdvancedButton}
                    </Button>
                    <Button size="xs" variant="outline" color="yellow" onClick={() => setResetOpen(true)}>
                      {sv.simple.priorities.overridesAlert.resetButton}
                    </Button>
                  </Group>
                </Stack>
              </Alert>
            )}

            {!data.customWeightsActive && data.otherOverridesActive && (
              <Text size="xs" c="dimmed" mb="sm">
                {sv.simple.priorities.otherOverridesNote}
              </Text>
            )}

            <PriorityRankList
              order={displayOrder}
              priorities={priorities}
              disabled={data.customWeightsActive}
              onMove={handleMove}
              onReorder={handleReorder}
            />

            <Accordion mt="md" variant="separated">
              <Accordion.Item value="interpretation">
                <Accordion.Control>{sv.simple.priorities.interpretationHeading}</Accordion.Control>
                <Accordion.Panel>
                  <Stack gap="sm">
                    {orderedPriorities.map((row) => (
                      <div key={row.key} data-testid="priority-summary-row">
                        <Text fw={600} size="sm">
                          {row.labelSv}
                        </Text>
                        <Text size="sm" c="dimmed">
                          {row.summarySv}
                        </Text>
                      </div>
                    ))}
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            </Accordion>
          </>
        )}
      </Card>

      <DeleteConfirmModal
        opened={resetOpen}
        title={sv.simple.priorities.resetConfirm.title}
        message={sv.simple.priorities.resetConfirm.message}
        errorMessage={resetError}
        confirmLabel={sv.simple.priorities.resetConfirm.confirmLabel}
        loading={setPriorityOrder.isPending}
        onClose={() => {
          setResetOpen(false);
          setResetError(null);
        }}
        onConfirm={handleResetConfirm}
      />
    </Stack>
  );
}
