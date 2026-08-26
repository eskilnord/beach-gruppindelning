import { useState } from "react";
import { Badge, Button, Card, Group, Loader, Select, Stack, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useCommentSuggestions } from "../../../api/commentSuggestions";
import { useUpdateParticipantFieldValues } from "../../../api/fieldValues";
import { useUpdateParticipant } from "../../../api/participants";
import type { CommentSuggestion, FieldValueView } from "../../../api/types";
import { HelpTip } from "../../../components/HelpTip";
import { sv } from "../../../i18n/sv";
import { useIsSimpleMode } from "../../../lib/uiMode/useUiMode";
import { addDismissedSuggestion, readDismissedSuggestions, suggestionDismissalId } from "./dismissedSuggestionsStorage";

/** What changed on successful apply, so the drawer can keep its OWN draft state consistent without
 *  losing unrelated unsaved edits (review fix MAJOR 5) - see {@link CommentSuggestionListProps
 *  onApplied}. */
export type SuggestionApplied = { kind: "field"; fieldKey: string; value: unknown } | { kind: "flag" };

interface CommentSuggestionListProps {
  planId: string;
  participantId: string;
  /** The drawer's currently LOADED (server-truth) field values - suggestions merge new target ids
   *  into these arrays, never into the possibly-unsaved draft (WP2 spec: "merge into the CURRENT
   *  array value from the drawer's loaded field values"). */
  fieldValues: FieldValueView[];
  /** {@code true} while the drawer's OWN field-values query is refetching (review fix minor 2) - the
   *  apply buttons stay disabled through that window too, not just while a mutation is in flight. */
  fieldValuesFetching: boolean;
  /** Called after a successful apply so {@code ParticipantDrawer} can merge the change into BOTH
   *  {@code customDraft} and {@code originalCustom} (or {@code structuredDraft}/{@code
   *  originalStructured} for the two flag kinds) for the affected key - review fix MAJOR 5. Without
   *  this, the drawer's own resync effect either clobbers unsaved edits (fixed by the dirty-guard
   *  there) or leaves the Switch/field showing a stale value until the next full reload. */
  onApplied: (change: SuggestionApplied) => void;
}

/**
 * v0.6.0 F4 (M-S4): pure filter for which suggestion `kind`s should render in the current uiMode -
 * COACH_WISH/COACH_AVOID (the only two kinds with a "COACH_" prefix, see CommentSuggestion.kind's
 * schema) are ADVANCED-only, matching CustomFieldEditor's coachRelation gating one screen over.
 * Exported (rather than inlined in the component) so it's independently unit-testable without a full
 * CommentSuggestion fixture - see CommentSuggestionList.test.tsx.
 */
export function visibleSuggestionKinds(kinds: string[], isSimple: boolean): string[] {
  return isSimple ? kinds.filter((kind) => !kind.startsWith("COACH_")) : kinds;
}

function currentArray(fieldValues: FieldValueView[], fieldKey: string | undefined): string[] {
  if (!fieldKey) {
    return [];
  }
  const value: unknown = fieldValues.find((fv) => fv.key === fieldKey)?.value;
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function descriptionFor(suggestion: CommentSuggestion, chosenName: string | null): string {
  const templates = sv.participants.suggestions.templates;
  const name = chosenName ?? suggestion.targets[0]?.displayName ?? "";
  switch (suggestion.kind) {
    case "PLAY_WITH":
      return templates.PLAY_WITH(name);
    case "MUST_PLAY_WITH":
      return templates.MUST_PLAY_WITH(name);
    case "AVOID_PLAY_WITH":
      return templates.AVOID_PLAY_WITH(name);
    case "COACH_WISH":
      return templates.COACH_WISH(name);
    case "COACH_AVOID":
      return templates.COACH_AVOID(name);
    case "TIME_CANNOT":
      return templates.TIME_CANNOT(suggestion.timeSlotIds.length);
    case "TIME_PREFER":
      return templates.TIME_PREFER(suggestion.timeSlotIds.length);
    case "NEW_TO_CLUB":
      return templates.NEW_TO_CLUB;
    case "LEVEL_CHANGE":
      return templates.LEVEL_CHANGE;
    case "INJURY_NOTE":
      return templates.INJURY_NOTE;
    default:
      return suggestion.matchedText;
  }
}

/**
 * "Tolkningsförslag" (WP2) — local, rule-based (NOT AI) proposals parsed from the participant's
 * imported comment, rendered under it in the Deltagarvy drawer whenever a comment exists. Every
 * suggestion is non-binding: nothing is written until the council clicks "Lägg till", which goes
 * straight through the EXISTING field-values PUT / participant PATCH hooks (never a bespoke write
 * path) — see backend {@code CommentSuggestionService}'s class javadoc for the full privacy
 * contract. B18.3 (v0.6.0 audit-fix batch B): dismissals persist per plan+suggestion in localStorage
 * (dismissedSuggestionsStorage.ts, fail-safe try/catch throughout) so a dismissed suggestion doesn't
 * resurrect every time the drawer is reopened - previously this was session-local React state only,
 * reset on every remount (this component still remounts per participant via the drawer's
 * `key={participant.id}`, which is why the persisted set is re-read from storage on mount).
 */
export function CommentSuggestionList({ planId, participantId, fieldValues, fieldValuesFetching, onApplied }: CommentSuggestionListProps) {
  const suggestionsQuery = useCommentSuggestions(planId, participantId);
  const updateFieldValues = useUpdateParticipantFieldValues(planId, participantId);
  const updateParticipant = useUpdateParticipant(planId);
  const [dismissed, setDismissed] = useState<Set<string>>(() => readDismissedSuggestions(planId));
  const [chosenByFingerprint, setChosenByFingerprint] = useState<Record<string, string>>({});
  const isSimple = useIsSimpleMode();

  if (suggestionsQuery.isLoading) {
    return <Loader size="xs" />;
  }

  const allowedKinds = new Set(visibleSuggestionKinds((suggestionsQuery.data?.suggestions ?? []).map((s) => s.kind), isSimple));
  const suggestions = (suggestionsQuery.data?.suggestions ?? []).filter(
    (s) => !dismissed.has(suggestionDismissalId(participantId, s.fingerprint)) && allowedKinds.has(s.kind),
  );
  if (suggestions.length === 0) {
    return null;
  }

  // Review fix (minor 2): while ANY apply mutation is in flight, or either the suggestions list or
  // the drawer's field-values are refetching (both happen right after a successful apply), every
  // "Lägg till" button in the list is disabled - prevents a double-apply race from a second click
  // landing before the refreshed alreadyApplied/candidate state comes back.
  const applying = updateFieldValues.isPending || updateParticipant.isPending;
  const busy = applying || suggestionsQuery.isFetching || fieldValuesFetching;

  const handleApply = async (suggestion: CommentSuggestion, chosenId: string | null, needsPick: boolean) => {
    // Review fix (minor 3): this branch is meant to be unreachable (the button is disabled whenever
    // it would apply), but a silent no-op on a real click is worse than a loud failure - guard it
    // explicitly rather than trusting every future refactor to preserve the disabled condition.
    if (!chosenId && needsPick) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: sv.participants.suggestions.applyFailed,
      });
      return;
    }
    try {
      // B18.2 (v0.6.0 audit-fix batch B): every real-write branch below flips this to true and gets
      // the same "Tillagd ✓" confirmation toast - the defensive fallback (empty targets) is an
      // error, not a success, and must never show it.
      let applied = false;
      if (suggestion.kind === "LEVEL_CHANGE" || suggestion.kind === "INJURY_NOTE") {
        await updateParticipant.mutateAsync({ id: participantId, body: { manualReviewFlag: true } });
        onApplied({ kind: "flag" });
        applied = true;
      } else if (suggestion.kind === "NEW_TO_CLUB") {
        const fieldKey = suggestion.fieldKey as string;
        await updateFieldValues.mutateAsync({ [fieldKey]: true });
        onApplied({ kind: "field", fieldKey, value: true });
        applied = true;
      } else if (suggestion.kind === "TIME_CANNOT" || suggestion.kind === "TIME_PREFER") {
        const fieldKey = suggestion.fieldKey as string;
        const merged = Array.from(new Set([...currentArray(fieldValues, fieldKey), ...suggestion.timeSlotIds]));
        await updateFieldValues.mutateAsync({ [fieldKey]: merged });
        onApplied({ kind: "field", fieldKey, value: merged });
        applied = true;
      } else if (chosenId) {
        const fieldKey = suggestion.fieldKey as string;
        const merged = Array.from(new Set([...currentArray(fieldValues, fieldKey), chosenId]));
        await updateFieldValues.mutateAsync({ [fieldKey]: merged });
        onApplied({ kind: "field", fieldKey, value: merged });
        applied = true;
      } else {
        // Defensive: a target-based suggestion with a single HIGH candidate always has chosenId set
        // below; only an unexpected empty-targets response could reach here.
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: sv.participants.suggestions.applyFailed,
        });
      }
      if (applied) {
        notifications.show({ color: "green", message: sv.participants.suggestions.applySuccess });
      }
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.participants.suggestions.applyFailed,
      });
    }
  };

  return (
    <Stack gap="xs">
      <Group gap={4}>
        <Title order={6}>{sv.participants.suggestions.heading}</Title>
        <HelpTip label={sv.participants.suggestions.heading}>{sv.participants.suggestions.helpTip}</HelpTip>
      </Group>
      {suggestions.map((suggestion) => {
        // Review fix (minor 1): already-applied candidates are removed from the picker entirely -
        // one unrelated pre-existing entry must never block applying a DIFFERENT, still-unapplied
        // candidate.
        const pickableTargets = suggestion.targets.filter((t) => !t.applied);
        const needsPick = suggestion.confidence === "UNCERTAIN" && pickableTargets.length > 1;
        const chosenId =
          chosenByFingerprint[suggestion.fingerprint] ?? (pickableTargets.length === 1 ? pickableTargets[0].id : null);
        const chosenName = suggestion.targets.find((t) => t.id === chosenId)?.displayName ?? null;
        return (
          <Card key={suggestion.fingerprint} withBorder padding="xs">
            <Stack gap={4}>
              <Group justify="space-between" wrap="nowrap">
                <Text size="sm">{descriptionFor(suggestion, chosenName)}</Text>
                {suggestion.confidence === "UNCERTAIN" && (
                  <Badge color="yellow" variant="light">
                    {sv.participants.suggestions.uncertainBadge}
                  </Badge>
                )}
              </Group>
              <Text size="xs" c="dimmed" fs="italic">
                &ldquo;{suggestion.matchedText}&rdquo;
              </Text>
              {needsPick && (
                <Select
                  placeholder={sv.participants.suggestions.pickCandidatePlaceholder}
                  data={pickableTargets.map((t) => ({ value: t.id, label: t.displayName }))}
                  value={chosenId}
                  onChange={(value) => setChosenByFingerprint((prev) => ({ ...prev, [suggestion.fingerprint]: value ?? "" }))}
                  size="xs"
                />
              )}
              <Group gap="xs" justify="flex-end">
                <Button
                  size="xs"
                  variant="subtle"
                  onClick={() => {
                    const dismissalId = suggestionDismissalId(participantId, suggestion.fingerprint);
                    addDismissedSuggestion(planId, dismissalId);
                    setDismissed((prev) => new Set(prev).add(dismissalId));
                  }}
                >
                  {sv.participants.suggestions.dismissButton}
                </Button>
                <Button
                  size="xs"
                  disabled={
                    busy ||
                    suggestion.alreadyApplied ||
                    (needsPick && !chosenId) ||
                    // Only a target-based kind (PLAY_WITH/COACH_* family) can have "all candidates
                    // already applied" - flag/time/NEW_TO_CLUB kinds always have an empty `targets`
                    // array by design and must never be disabled by this check.
                    (suggestion.targets.length > 0 && pickableTargets.length === 0)
                  }
                  loading={applying}
                  onClick={() => void handleApply(suggestion, chosenId, needsPick)}
                >
                  {suggestion.alreadyApplied
                    ? sv.participants.suggestions.alreadyAppliedButton
                    : sv.participants.suggestions.applyButton}
                </Button>
              </Group>
            </Stack>
          </Card>
        );
      })}
    </Stack>
  );
}
