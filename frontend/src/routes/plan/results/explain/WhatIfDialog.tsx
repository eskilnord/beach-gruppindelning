import { useEffect, useState } from "react";
import { Alert, Badge, Button, Divider, Group, Loader, Modal, Select, Stack, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useLockPlayerAssignment, useMoveAssignment } from "../../../../api/assignments";
import { useWhatIfMove } from "../../../../api/whatif";
import { ApiError } from "../../../../api/client";
import { sv } from "../../../../i18n/sv";
import { DeleteConfirmModal } from "../../../../components/DeleteConfirmModal";
import { useIsSimpleMode } from "../../../../lib/uiMode/useUiMode";
import { formatScoreDelta } from "./formatScoreDelta";
import type { GroupOption } from "./ExplainDrawer";
import type { ConstraintMessageView, ScoreDeltaView } from "../../../../api/types";

const WAITLIST_VALUE = "__WAITLIST__";

/** v0.6.0 audit-fix batch C (C12, P1): every coach-family constraint key is spelled `coach...`
 *  (`ConstraintKeys.java`, `coachNoOverlap`/`coachWishRequired`/etc.) except one legacy outlier,
 *  `savedPlanCoachBlocked` - `includes` (not `startsWith`) catches that one too. Filtering on this
 *  DATA field (unlike SimpleExplainBody's own coach filters, which have no such field to key off and
 *  must text-sniff the rendered Swedish instead) is the more robust choice wherever it's available. */
function isCoachConstraintMessage(message: ConstraintMessageView): boolean {
  return message.key.toLowerCase().includes("coach");
}

/** Green = strictly better, gray = exactly zero (the NEUTRAL case - no-change must not read as an
 *  improvement), red = worse or breaks hard. Same dominance order as HardMediumSoftLongScore. */
function scoreDeltaColor(delta: ScoreDeltaView): string {
  if (delta.hard < 0 || (delta.hard === 0 && delta.medium < 0) || (delta.hard === 0 && delta.medium === 0 && delta.soft < 0)) {
    return "red";
  }
  if (delta.hard === 0 && delta.medium === 0 && delta.soft === 0) {
    return "gray";
  }
  return "green";
}

interface WhatIfDialogProps {
  planId: string;
  runId: string | undefined;
  participantProfileId: string | null;
  participantName: string;
  /** The participant's CURRENT group, or null if they're currently waitlisted - excluded from the
   *  target picker, and gates whether "flytta till kölista" is offered as a target at all. */
  currentGroupId: string | null;
  allGroups: GroupOption[];
  onClose: () => void;
  /** v0.6.0 F5 (M-S5): pre-selects the target group picker (and so immediately fires the
   *  consequence query, same as if the user had just picked it) - wired from SimpleExplainBody's
   *  per-wish "Testa att flytta" CTA with the wish's `bestCandidateGroupId`. Omitted/`undefined`
   *  (default) preserves today's behavior: the picker opens empty. */
  initialTargetGroupId?: string | null;
}

/**
 * The M7 what-if dialog ([Testa flytt], kravspec §18.1): pick a target group, get a live consequence
 * report from {@code POST .../whatif/move} (score delta, wouldBreakHard, group size/level-spread
 * changes, newly broken/fixed constraints), then act on {@code suggestedActions} (§18.2/§18.3):
 * behåll nuvarande (close), flytta ändå (the actual mutating manual move, confirmed first if it would
 * break a hard rule), or lås & markera för omoptimering (move + lock, suggesting a re-solve).
 */
export function WhatIfDialog({
  planId,
  runId,
  participantProfileId,
  participantName,
  currentGroupId,
  allGroups,
  onClose,
  initialTargetGroupId,
}: WhatIfDialogProps) {
  const opened = participantProfileId !== null;
  return (
    <Modal opened={opened} onClose={onClose} title={sv.results.whatIf.title(participantName)} size="lg" data-testid="whatif-dialog">
      {opened && (
        <WhatIfDialogBody
          key={`${runId ?? ""}:${participantProfileId}`}
          planId={planId}
          runId={runId}
          participantProfileId={participantProfileId}
          participantName={participantName}
          currentGroupId={currentGroupId}
          allGroups={allGroups}
          onClose={onClose}
          initialTargetGroupId={initialTargetGroupId}
        />
      )}
    </Modal>
  );
}

interface WhatIfDialogBodyProps {
  planId: string;
  runId: string | undefined;
  participantProfileId: string;
  participantName: string;
  currentGroupId: string | null;
  allGroups: GroupOption[];
  onClose: () => void;
  initialTargetGroupId?: string | null;
}

function WhatIfDialogBody({
  planId,
  runId,
  participantProfileId,
  participantName,
  currentGroupId,
  allGroups,
  onClose,
  initialTargetGroupId,
}: WhatIfDialogBodyProps) {
  // v0.6.0 F5 review fix (FIX 5, MAJOR): only seed the picker from `initialTargetGroupId` when it's
  // actually one of the CURRENT run's groups - a stale cached explain-drawer response (e.g. from
  // before a re-solve regenerated groups) could otherwise prefill a group id that no longer exists,
  // silently selecting nothing real and never firing the consequence query for the user's chosen
  // group either.
  const validInitialTargetGroupId =
    initialTargetGroupId != null && allGroups.some((g) => g.id === initialTargetGroupId) ? initialTargetGroupId : null;
  const [targetValue, setTargetValue] = useState<string | null>(validInitialTargetGroupId);
  const [confirmBreakHard, setConfirmBreakHard] = useState<"MOVE_ANYWAY" | "LOCK_AND_RESOLVE" | null>(null);
  // v0.6.0 audit-fix batch C (C12, P1): SIMPLE hides raw score/spread numbers and coach specifics
  // (see the render branches below) - ADVANCED stays byte-identical to before this finding.
  const isSimple = useIsSimpleMode();

  const consequence = useWhatIfMove(planId);
  const moveAssignment = useMoveAssignment(planId);
  const lockAssignment = useLockPlayerAssignment(planId);

  const targetOptions = [
    ...allGroups.filter((g) => g.id !== currentGroupId).map((g) => ({ value: g.id, label: g.name })),
    ...(currentGroupId !== null ? [{ value: WAITLIST_VALUE, label: sv.results.whatIf.waitlistOption }] : []),
  ];

  useEffect(() => {
    if (targetValue && runId) {
      consequence.mutate({
        participantProfileId,
        targetGroupId: targetValue === WAITLIST_VALUE ? undefined : targetValue,
        runId,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetValue, runId]);

  const targetGroupId = targetValue === WAITLIST_VALUE ? null : targetValue;
  const targetGroupName = targetGroupId ? (allGroups.find((g) => g.id === targetGroupId)?.name ?? null) : null;

  const applyMove = (thenLock: boolean) => {
    moveAssignment.mutate(
      { participantProfileId, groupId: targetGroupId },
      {
        onSuccess: () => {
          if (thenLock && targetGroupId) {
            lockAssignment.mutate(
              { participantProfileId, groupId: targetGroupId },
              {
                onSuccess: () => {
                  notifications.show({ color: "green", message: sv.results.whatIf.lockAndResolveSuccess(participantName) });
                  onClose();
                },
                onError: (error) => {
                  notifications.show({
                    color: "red",
                    title: sv.common.error,
                    message: error instanceof ApiError ? error.message : sv.results.groupCard.lockFailed,
                  });
                },
              },
            );
            return;
          }
          notifications.show({ color: "green", message: sv.results.whatIf.moveSuccess(participantName, targetGroupName) });
          onClose();
        },
        onError: (error) => {
          notifications.show({
            color: "red",
            title: sv.common.error,
            message: error instanceof ApiError ? error.message : sv.results.whatIf.moveFailed,
          });
        },
      },
    );
  };

  const handleMoveAnyway = () => {
    if (consequence.data?.wouldBreakHard) {
      setConfirmBreakHard("MOVE_ANYWAY");
      return;
    }
    applyMove(false);
  };

  const handleLockAndResolve = () => {
    if (consequence.data?.wouldBreakHard) {
      setConfirmBreakHard("LOCK_AND_RESOLVE");
      return;
    }
    applyMove(true);
  };

  const applying = moveAssignment.isPending || lockAssignment.isPending;
  const data = consequence.data;

  return (
    <Stack gap="md">
      <Select
        label={sv.results.whatIf.targetGroupLabel}
        placeholder={sv.results.whatIf.targetGroupPlaceholder}
        data={targetOptions}
        value={targetValue}
        onChange={setTargetValue}
        // User feedback v0.4.1: withinPortal:false clipped the dropdown against the Modal's
        // overflow when there were many groups, with no way to scroll to the rest. Portalling (the
        // Mantine 8 default) renders the dropdown outside the modal's DOM subtree so it isn't
        // clipped, and keeps its default max-height + internal scroll. Confirmed no test queries
        // options scoped to the dialog element (both the e2e spec and any vitest coverage query
        // options at page/document level), so the portal move doesn't break anything.
        comboboxProps={{ withinPortal: true }}
        data-testid="whatif-target-select"
      />

      {consequence.isPending && <Loader size="sm" data-testid="whatif-consequence-loading" />}
      {consequence.isError && (
        <Alert color="red">
          {consequence.error instanceof ApiError ? consequence.error.message : sv.results.whatIf.consequenceLoadFailed}
        </Alert>
      )}

      {data && (
        <div data-testid="whatif-consequence">
          <Title order={5}>{sv.results.whatIf.consequenceHeading}</Title>

          {data.wouldBreakHard && (
            <Alert color="red" mt="xs" data-testid="whatif-would-break-hard">
              {sv.results.whatIf.wouldBreakHardAlert}
            </Alert>
          )}

          {/* v0.6.0 audit-fix batch C (C12, P1): the raw Totalpoäng line is jargon (an unscaled,
              unbounded weighted score) a non-technical admin can't interpret - hidden in SIMPLE,
              which already has the wouldBreakHard alert plus the plain-language broken/fixed lists
              below to answer "is this a good idea?" without a number. */}
          {!isSimple && (
            <Group gap={6} mt="xs" wrap="nowrap">
              <Text>{sv.results.whatIf.scoreDeltaLabel}:</Text>
              {/* Gray for an exactly-zero delta - mirrors the NEUTRAL verdict (backend M7-review
                  extension, "påverkar inte totalpoängen") rather than presenting no-change as an
                  improvement (green). */}
              <Badge color={scoreDeltaColor(data.scoreDelta)}>{formatScoreDelta(data.scoreDelta)}</Badge>
            </Group>
          )}

          {data.groupSizeChanges.length > 0 && (
            <div>
              <Text size="sm" fw={500} mt="sm">
                {sv.results.whatIf.groupSizeChangesHeading}
              </Text>
              {data.groupSizeChanges.map((c) => (
                <Text size="sm" key={c.groupId}>
                  {sv.results.whatIf.groupSizeChangeLine(c.name, c.from, c.to, c.max ?? null)}
                </Text>
              ))}
            </div>
          )}

          {/* v0.6.0 audit-fix batch C (C12, P1): "spread" (nivåspridning, a raw point unit) is the
              same class of jargon as Totalpoäng above - SIMPLE-hidden. */}
          {!isSimple && data.levelSpreadChanges.length > 0 && (
            <div>
              <Text size="sm" fw={500} mt="sm">
                {sv.results.whatIf.levelSpreadChangesHeading}
              </Text>
              {data.levelSpreadChanges.map((c) => (
                <Text size="sm" key={c.groupId}>
                  {sv.results.whatIf.levelSpreadChangeLine(c.name, c.from, c.to)}
                </Text>
              ))}
            </div>
          )}

          {data.newlyBroken.length > 0 && (
            <div>
              <Text size="sm" fw={500} mt="sm">
                {isSimple ? sv.results.whatIf.simple.newlyBrokenHeading : sv.results.explain.newlyBrokenHeading}
              </Text>
              {(isSimple ? data.newlyBroken.filter((m) => !isCoachConstraintMessage(m)) : data.newlyBroken).map((m, i) => (
                <Text size="sm" c="red" key={`${m.key}-${i}`}>
                  {m.messageSv}
                </Text>
              ))}
              {/* v0.6.0 audit-fix batch C (C12, P1): coach-family rows collapse into one honest
                  count-line rather than naming a coach - the same substitution-not-hiding principle
                  as SimpleExplainBody's own trainerReasonSubstitute. */}
              {isSimple && data.newlyBroken.some(isCoachConstraintMessage) && (
                <Text size="sm" c="red">
                  {sv.results.whatIf.simple.coachRowsCollapsed(data.newlyBroken.filter(isCoachConstraintMessage).length)}
                </Text>
              )}
            </div>
          )}

          {data.newlyFixed.length > 0 && (
            <div>
              <Text size="sm" fw={500} mt="sm">
                {isSimple ? sv.results.whatIf.simple.newlyFixedHeading : sv.results.explain.newlyFixedHeading}
              </Text>
              {(isSimple ? data.newlyFixed.filter((m) => !isCoachConstraintMessage(m)) : data.newlyFixed).map((m, i) => (
                <Text size="sm" c="green" key={`${m.key}-${i}`}>
                  {m.messageSv}
                </Text>
              ))}
              {isSimple && data.newlyFixed.some(isCoachConstraintMessage) && (
                <Text size="sm" c="green">
                  {sv.results.whatIf.simple.coachRowsCollapsed(data.newlyFixed.filter(isCoachConstraintMessage).length)}
                </Text>
              )}
            </div>
          )}
        </div>
      )}

      <Divider />

      {/* v0.6.0 audit-fix batch C (C12, P1): SIMPLE drops "Lås & markera för omoptimering" entirely -
          an ADVANCED-only action (locking + implicitly flagging the plan for a future re-solve is a
          two-step workflow concept SIMPLE never otherwise exposes) - and makes "Behåll nuvarande" the
          visually primary choice, "Flytta ändå" secondary, since staying put is the safer default a
          non-technical admin should reach for first. ADVANCED keeps the original three-button variant
          set byte-identical. */}
      <Group justify="flex-end">
        <Button variant={isSimple ? "filled" : "default"} onClick={onClose}>
          {sv.results.whatIf.actions.keep}
        </Button>
        <Button
          variant={isSimple ? "light" : "outline"}
          disabled={!data || applying}
          loading={applying && !confirmBreakHard}
          onClick={handleMoveAnyway}
        >
          {sv.results.whatIf.actions.moveAnyway}
        </Button>
        {!isSimple && (
          <Button disabled={!data || !targetGroupId || applying} loading={applying && !confirmBreakHard} onClick={handleLockAndResolve}>
            {sv.results.whatIf.actions.lockAndResolve}
          </Button>
        )}
      </Group>

      <DeleteConfirmModal
        opened={confirmBreakHard !== null}
        title={sv.results.whatIf.confirmBreakHard.title}
        message={sv.results.whatIf.confirmBreakHard.message(participantName, targetGroupName ?? sv.results.whatIf.waitlistOption)}
        confirmLabel={sv.results.whatIf.actions.moveAnyway}
        loading={applying}
        onClose={() => setConfirmBreakHard(null)}
        onConfirm={() => {
          const thenLock = confirmBreakHard === "LOCK_AND_RESOLVE";
          setConfirmBreakHard(null);
          applyMove(thenLock);
        }}
      />
    </Stack>
  );
}
