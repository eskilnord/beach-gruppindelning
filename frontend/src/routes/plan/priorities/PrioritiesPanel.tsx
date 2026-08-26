import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Accordion, Alert, Button, Card, Group, List, Loader, Modal, Stack, Text, Title } from "@mantine/core";
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
import { useConfirmedAdvancedMode } from "../../../components/uimode/useConfirmedAdvancedMode";
import { sv } from "../../../i18n/sv";
import { PriorityRankList } from "./PriorityRankList";
import { arraysEqual, isPermutation, moveItem, reorder } from "./priorityOrder";

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
 *
 * v0.6.0 audit batch D: two independent reset actions now live here, each with its own confirm
 * modal - {@link handleResetConfirm} ("Ersätt de anpassade inställningarna?", D1) restores the
 * weights to match the shown, backend-INFERRED order while `customWeightsActive`; {@link
 * handleResetToDefaultConfirm} ("Återställ till standardordning?", D4) restores
 * `PriorityOrderView.defaultOrder` regardless of customWeightsActive, and is only offered once the
 * displayed order has actually drifted from that default.
 */
export function PrioritiesPanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { requestAdvancedMode, confirmModal: advancedModeConfirmModal } = useConfirmedAdvancedMode();

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
  const [resetToDefaultOpen, setResetToDefaultOpen] = useState(false);
  const [resetToDefaultError, setResetToDefaultError] = useState<string | null>(null);

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
    setResetToDefaultOpen(false);
    setResetToDefaultError(null);
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

  // v0.6.0 audit batch D (D2): routed through the shared confirm gate (useConfirmedAdvancedMode)
  // instead of switching straight to ADVANCED - this button used to be the one "Öppna avancerat
  // läge" affordance in the whole app that skipped the deliberate-opt-in confirm every other entry
  // point (UiModeSwitch) already has.
  const handleOpenAdvanced = () => {
    requestAdvancedMode(() => {
      // No falt sub-tab deep-link mechanism exists (FieldsPanel.tsx's Tabs are plain uncontrolled
      // state, no URL param) - land on the tab itself, same as any other advanced-only deep link.
      navigate(`/plans/${planId}/falt`);
    });
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
        // v0.6.0 audit batch D (D1): "Reset success sets 'Sparat ✓'" - same terminal status the
        // ordinary autosave path shows, so the admin sees the same confirmation regardless of which
        // action actually saved the order.
        setSaveStatus({ kind: "saved" });
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

  // v0.6.0 audit batch D (D4): a second, independent reset - restores `data.defaultOrder` regardless
  // of `customWeightsActive` (unlike {@link handleResetConfirm} above, which only ever applies while
  // customWeightsActive and restores the shown INFERRED order, not necessarily the default one).
  const handleResetToDefaultConfirm = () => {
    if (!planId || !data) {
      return;
    }
    setResetToDefaultError(null);
    const orderToSave = data.defaultOrder;
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
        setSaveStatus({ kind: "saved" });
        setResetToDefaultOpen(false);
      },
      onError: (error) => {
        if (saveTokenRef.current !== token) {
          return;
        }
        setDirty(false);
        setResetToDefaultError(error instanceof ApiError ? error.message : sv.simple.priorities.resetToDefaultFailed);
      },
    });
  };

  // v0.6.0 F3 review fix (FIX 10, MINOR): the "Detaljer" accordion (and, per audit batch D, both
  // reset-confirm modals' priority lists) is sorted by the currently DISPLAYED order (what the admin
  // sees in the ranked list above), not `priorities`'s raw server array order (which is rank-stable
  // from the last GET/PUT and can visibly diverge from `displayOrder` while a local reorder is
  // pending save).
  const priorityByKey = new Map(priorities.map((row) => [row.key, row]));
  const orderedPriorities = displayOrder
    .map((key) => priorityByKey.get(key))
    .filter((row): row is PriorityRowView => row !== undefined);
  const defaultOrderedPriorities = (data?.defaultOrder ?? [])
    .map((key) => priorityByKey.get(key))
    .filter((row): row is PriorityRowView => row !== undefined);

  // v0.6.0 audit batch D (D4): only offered once the shown order has actually drifted from the
  // backend's default AND there's no customWeightsActive override already governing the list (that
  // case has its own, different reset button/modal - see handleResetConfirm above).
  const showResetToDefaultButton = !!data && !data.customWeightsActive && !arraysEqual(displayOrder, data.defaultOrder);

  // v0.6.0 final pre-release fix round (FIX 5, MINOR): while an optimistic reorder is still pending
  // (dirty) OR actively saving, `orderedPriorities`'s per-row `summarySv` sentences in the accordion
  // below are stale relative to the row the admin just visibly dragged/moved - suppressed in favor of
  // a plain "Uppdateras…" line until the save either lands (dirty clears) or reverts.
  const accordionUpdating = dirty || saveStatus.kind === "saving";

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={3} mb={4}>
          {sv.simple.priorities.heading}
        </Title>
        {/* v0.6.0 audit batch D (D4): suppresses the "högst upp får störst betydelse" sentence while
            customWeightsActive - the order genuinely isn't what's driving optimization right now, so
            that claim would directly contradict the overrides alert just below it. */}
        <Text c="dimmed" size="sm" mb="md">
          {data?.customWeightsActive ? sv.simple.priorities.introReduced : sv.simple.priorities.intro}
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

            {data.customWeightsActive && (
              <Text size="xs" c="dimmed" mb={4} data-testid="priority-locked-note">
                {sv.simple.priorities.lockedNote}
              </Text>
            )}

            <PriorityRankList
              order={displayOrder}
              priorities={priorities}
              disabled={data.customWeightsActive}
              onMove={handleMove}
              onReorder={handleReorder}
            />

            {/* v0.6.0 audit batch D (D4): moved out of the header's top-right corner - adjacent to
                the list it describes, instead of a spot the admin's eye has to travel back up to. */}
            <div data-testid="priority-save-status" role="status" aria-live="polite" style={{ marginTop: 8 }}>
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

            {showResetToDefaultButton && (
              <Button
                variant="subtle"
                color="gray"
                size="xs"
                mt={4}
                px={0}
                onClick={() => setResetToDefaultOpen(true)}
                data-testid="priority-reset-to-default-button"
              >
                {sv.simple.priorities.resetToDefaultButton}
              </Button>
            )}

            {/* v0.6.0 audit batch D (D4): the accordion duplicates the per-row rankMeaning sentences
                while customWeightsActive is true AND the order isn't authoritative anyway - suppressed
                in that state, same condition as `introReduced` above. */}
            {!data.customWeightsActive && (
              <Accordion mt="md" variant="separated">
                <Accordion.Item value="interpretation">
                  <Accordion.Control>{sv.simple.priorities.interpretationHeading}</Accordion.Control>
                  <Accordion.Panel>
                    {/* v0.6.0 final pre-release fix round (FIX 5, MINOR): a stale per-row summary
                        sentence under a row the admin just visibly reordered would contradict what
                        they just saw happen - a dimmed "Uppdateras…" line instead, while the save is
                        still pending. */}
                    {accordionUpdating ? (
                      <Text size="sm" c="dimmed" data-testid="priority-accordion-updating">
                        {sv.simple.priorities.accordionUpdating}
                      </Text>
                    ) : (
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
                    )}
                  </Accordion.Panel>
                </Accordion.Item>
              </Accordion>
            )}
          </>
        )}
      </Card>

      {/* v0.6.0 audit batch D (D1): a bespoke modal (not the generic DeleteConfirmModal) so the
          about-to-apply order can be rendered as an actual list between the intro and trailing text,
          not folded into one paragraph. */}
      <Modal
        opened={resetOpen}
        onClose={() => {
          setResetOpen(false);
          setResetError(null);
        }}
        title={sv.simple.priorities.resetConfirm.title}
        centered
      >
        {resetError && (
          <Alert color="red" mb="sm">
            {resetError}
          </Alert>
        )}
        <Text size="sm" mb="sm">
          {sv.simple.priorities.resetConfirm.introText}
        </Text>
        <List size="sm" mb="sm" data-testid="priority-reset-confirm-list">
          {orderedPriorities.map((row, index) => (
            <List.Item key={row.key}>{`${index + 1}. ${row.labelSv}`}</List.Item>
          ))}
        </List>
        <Text size="sm" mb="lg">
          {sv.simple.priorities.resetConfirm.trailingText}
        </Text>
        <Group justify="flex-end">
          <Button
            variant="default"
            onClick={() => {
              setResetOpen(false);
              setResetError(null);
            }}
            disabled={setPriorityOrder.isPending}
          >
            {sv.common.cancel}
          </Button>
          <Button color="red" onClick={handleResetConfirm} loading={setPriorityOrder.isPending}>
            {sv.simple.priorities.resetConfirm.confirmLabel}
          </Button>
        </Group>
      </Modal>

      {/* v0.6.0 audit batch D (D4): the second, independent "restore the app's default order" reset. */}
      <Modal
        opened={resetToDefaultOpen}
        onClose={() => {
          setResetToDefaultOpen(false);
          setResetToDefaultError(null);
        }}
        title={sv.simple.priorities.resetToDefaultConfirm.title}
        centered
      >
        {resetToDefaultError && (
          <Alert color="red" mb="sm">
            {resetToDefaultError}
          </Alert>
        )}
        <Text size="sm" mb="sm">
          {sv.simple.priorities.resetToDefaultConfirm.introText}
        </Text>
        <List size="sm" mb="lg" data-testid="priority-reset-to-default-list">
          {defaultOrderedPriorities.map((row, index) => (
            <List.Item key={row.key}>{`${index + 1}. ${row.labelSv}`}</List.Item>
          ))}
        </List>
        <Group justify="flex-end">
          <Button
            variant="default"
            onClick={() => {
              setResetToDefaultOpen(false);
              setResetToDefaultError(null);
            }}
            disabled={setPriorityOrder.isPending}
          >
            {sv.common.cancel}
          </Button>
          <Button onClick={handleResetToDefaultConfirm} loading={setPriorityOrder.isPending}>
            {sv.simple.priorities.resetToDefaultConfirm.confirmLabel}
          </Button>
        </Group>
      </Modal>

      {advancedModeConfirmModal}
    </Stack>
  );
}
