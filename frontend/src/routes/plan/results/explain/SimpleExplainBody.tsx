import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Accordion, Alert, Anchor, Button, Group, Stack, Text, Title } from "@mantine/core";
import type { FactorView, PersonExplanationResponse, UnmetWishView } from "../../../../api/types";
import { sv } from "../../../../i18n/sv";
import type { GroupOption } from "./ExplainDrawer";
import { WaitlistNarrative } from "./WaitlistNarrative";

/** Positive factors beyond this count start collapsed behind "Visa fler" (F5 UX spec). */
const POSITIVE_FACTORS_COLLAPSE_AT = 3;

/**
 * v0.6.0 F5 review fix (FIX 1, BLOCKER): `FactorView` (positiveFactors/negativeFactors) carries NO
 * key/constraint-id field at all - see api/types.ts's `FactorView` alias and schema.d.ts's generated
 * shape, both just `{ messageSv: string }` - so unlike `UnmetWishView.wishId` (filterable by the
 * honest "COACH:" prefix below) there is no data field to filter a coach-identifying positive factor
 * on. This is a real backend gap (tracked here as a follow-up: `FactorView` needs the same kind of
 * `key`/family field `BrokenWishView` already has, so this can filter on DATA instead of TEXT in a
 * future explain milestone) - until then, the only honest option left is recognizing the backend's
 * own FIXED Swedish templates for a coach-wish match. Every one of JustificationMessages.java's coach
 * sentences (`coachWishMessage`/`coachWishFulfilledMessage`, all six MUST/CANNOT/WANT phrasings -
 * "fick önskad tränare", "fick inte önskad tränare", "fick förbjuden tränare", "måste ha tränare...
 * men fick det inte", "får tränare", "slipper förbjuden tränare") places the coach's name immediately
 * next to the literal noun "tränare" - and no OTHER constraint family's rendered sentence contains
 * that word (verified against every `case` in `JustificationMessages#toSwedish`/`#toSwedishAsFixed`;
 * the one near-miss, `TrainAndCoachClashJustification`'s "...tränar i grupp...", is the VERB "tränar"
 * with no trailing "e", so the word-boundary regex below does not match it). Only "fick önskad
 * tränare" can actually reach `positiveFactors` today (the only POSITIVE branch of
 * `coachWishMessage`) - "får tränare"/"slipper förbjuden tränare" are today only ever emitted by the
 * ADVANCED what-if consequence dialog (`toSwedishAsFixed`, MoveProbe), never this endpoint - but are
 * matched too as a defensive margin, since nothing here can assume that stays true.
 */
const COACH_FACTOR_PATTERN = /\btränare\b/;

function isCoachFactor(factor: FactorView): boolean {
  return COACH_FACTOR_PATTERN.test(factor.messageSv);
}

/** v0.6.0 audit-fix batch C (C10, P1): the backend's `addLevelMatchFactor` (ExplanationService.java)
 *  adds a RAW-NUMBERS sibling right behind its lay in-band sentence ("«Namn»s nivåscore 640,0 matchar
 *  «Grupp»s nivåspann 600,0–690,0") - kept for ADVANCED (which renders the same `positiveFactors`
 *  list in full), but SIMPLE must never show raw score numbers. No `key`/family field exists on
 *  `FactorView` to filter this on DATA (same backend gap `isCoachFactor` above already documents),
 *  so this is the same text-sniff fallback, matched against the one literal token every numeric
 *  level-match sentence contains ("nivåscore" - never appears in any other factor's wording). */
const LEVEL_NUMERIC_FACTOR_PATTERN = /nivåscore/;

function isLevelNumericFactor(factor: FactorView): boolean {
  return LEVEL_NUMERIC_FACTOR_PATTERN.test(factor.messageSv);
}

/** v0.6.0 audit-fix batch C (C12, P1): a defensive, DATA-independent backstop - even after the
 *  backend's own C11 coach-genericization, some unmet-wish `primaryReasonSv` sentence could still
 *  end up mentioning "tränar..." (e.g. a future OTHER-family narrative path this component can't
 *  anticipate). Substituted with an honest, still-true generic sentence rather than silently hidden
 *  (silence would misrepresent "we know why but won't say" as "we don't know why" - see truthfulness
 *  rule) or shown verbatim (which could leak a coach name into SIMPLE). Backend follow-up noted: a
 *  structured coach-flag on `UnmetWishView` would let this filter on data instead of text, same as
 *  the `nonCoachUnmetWishes` filter below already does via `wishId`. */
const TRAINER_MENTION_PATTERN = /tränar/i;

function primaryReasonSvForSimple(wish: UnmetWishView): string {
  return TRAINER_MENTION_PATTERN.test(wish.primaryReasonSv) ? sv.results.explain.simple.trainerReasonSubstitute : wish.primaryReasonSv;
}

interface SimpleExplainBodyProps {
  planId: string;
  data: PersonExplanationResponse;
  /** Every group in the CURRENT run (ExplainDrawer's own `allGroups` prop, threaded down) - v0.6.0
   *  F5 review fix (FIX 5, MAJOR): `bestCandidateGroupId`/`wishId`'s candidate groups could reference
   *  a group from a STALE cached explanation that no longer exists in the current run (a re-solve
   *  regenerated groups) - "Testa att flytta" must never be offered for a group id the current run
   *  doesn't actually have. */
  allGroups: GroupOption[];
  /** Opens the shared WhatIfDialog prefilled to a target group - see ExplainDrawer's own javadoc on
   *  this callback. */
  onTestMove: (participantProfileId: string, name: string, currentGroupId: string | null, initialTargetGroupId: string) => void;
}

/**
 * v0.6.0 F5 (M-S5): the SIMPLE-mode person-level explain body - a plain-language "why here" / "what
 * wasn't possible" narrative built entirely from server-rendered Swedish sentences
 * (PersonExplanationResponse.placementSummarySv/unmetWishes[]), rendered verbatim. Deliberately
 * omits everything ADVANCED-only: the "Tillämpade vikter" accordion, raw score deltas, the
 * alternatives list, "Varför inte...?", the indirect-factors section, weight badges, and
 * coachBindingSv lines - none of those are jargon-free enough for a non-technical council member,
 * and every fact they'd surface is either already folded into placementSummarySv/unmetWishes, or
 * stays reachable in ADVANCED for anyone who wants the full picture.
 */
export function SimpleExplainBody({ planId, data, allGroups, onTestMove }: SimpleExplainBodyProps) {
  const navigate = useNavigate();
  const [showAllPositive, setShowAllPositive] = useState(false);

  const selectedGroup = data.selectedGroup;
  const headline = selectedGroup
    ? sv.results.explain.simple.headline(data.name, selectedGroup.name, selectedGroup.timeLabelSv ?? null)
    : null;

  // v0.6.0 F5 review fix (FIX 1, BLOCKER): SIMPLE never names a coach - drop any positive factor
  // whose sentence is one of the backend's coach-wish templates (see isCoachFactor's own doc comment
  // above) before slicing for the "Visa fler" collapse.
  const nonCoachPositive = data.positiveFactors.filter((f) => !isCoachFactor(f));
  // v0.6.0 audit-fix batch C (C10, P1): `positiveFactors[0]` is, by construction of the backend's
  // `placementSummarySv` (ExplanationService#placementSummarySv is built directly FROM it), always
  // the exact fact already stated one sentence above in the "Därför hamnade hen här" summary -
  // dropped here so it isn't repeated a second time in this list. When that lead fact is the in-band
  // level-match, its raw-numbers sibling (kept for ADVANCED only, see isLevelNumericFactor's doc
  // comment) lands right behind it - filtered out below regardless of position, so SIMPLE never
  // shows a "triple repetition" (summary sentence, lay factor, numeric factor) of the same fact.
  const afterSummaryFactors = selectedGroup && nonCoachPositive.length > 0 ? nonCoachPositive.slice(1) : nonCoachPositive;
  const simplePositive = afterSummaryFactors.filter((f) => !isLevelNumericFactor(f));
  const visiblePositive = showAllPositive ? simplePositive : simplePositive.slice(0, POSITIVE_FACTORS_COLLAPSE_AT);
  const hasMorePositive = !showAllPositive && simplePositive.length > POSITIVE_FACTORS_COLLAPSE_AT;

  // v0.6.0 F5 review fix (FIX 1, BLOCKER): unmetWishes DOES carry a real, honest key to filter on -
  // CausalNarrator#wishId prefixes every COACH wish "COACH:<coachPersonSolverId>" (backend
  // CausalNarrator.java) - so this filter is data-derived, not text-sniffed.
  const nonCoachUnmetWishes = data.unmetWishes.filter((w) => !w.wishId.startsWith("COACH:"));

  // v0.6.0 F5 review fix (FIX 5, MAJOR): only offer "Testa att flytta" for a candidate group that
  // actually exists in the CURRENT run - see the `allGroups` prop's own doc comment.
  const knownGroupIds = new Set(allGroups.map((g) => g.id));

  const handleTestMove = (targetGroupId: string) => {
    onTestMove(data.participantProfileId, data.name, selectedGroup?.groupId ?? null, targetGroupId);
  };
  const handleChangePriorityOrder = () => {
    navigate(`/plans/${planId}/prioriteringar`);
  };

  return (
    <Stack gap="md">
      {headline && <Title order={4} data-testid="explain-why-headline">{headline}</Title>}

      {data.lockedNoticeSv && (
        <Alert color="blue" data-testid="explain-locked-notice">
          {data.lockedNoticeSv}
        </Alert>
      )}

      {selectedGroup ? (
        <div data-testid="explain-simple-summary">
          <Title order={5}>{sv.results.explain.simple.thereforeHeading}</Title>
          <Text size="sm" mb="xs">
            {data.placementSummarySv}
          </Text>
          {visiblePositive.map((f, i) => (
            <Text key={i} size="sm" c="green" data-testid="explain-positive-factor">
              <span aria-hidden="true">✓</span> {f.messageSv}
            </Text>
          ))}
          {hasMorePositive && (
            <Anchor component="button" type="button" size="sm" mt={4} onClick={() => setShowAllPositive(true)}>
              {sv.results.explain.simple.showMoreFactors}
            </Anchor>
          )}
        </div>
      ) : (
        // v0.6.0 F5 review fix (minor, waitlist heading level): WaitlistNarrative substitutes for
        // the headline (order 4) above in this branch - pass headingOrder={4} so its own section
        // heading matches, instead of the order-5 it defaults to as an embedded ADVANCED section.
        data.waitlist && <WaitlistNarrative waitlist={data.waitlist} name={data.name} headingOrder={4} />
      )}

      <div data-testid="explain-unmet-wishes">
        <Title order={5}>{sv.results.explain.simple.unmetWishesHeading}</Title>
        {nonCoachUnmetWishes.length === 0 ? (
          <Text size="sm" c="dimmed">
            {sv.results.explain.simple.noUnmetWishes}
          </Text>
        ) : (
          <Stack gap="sm" mt="xs">
            {nonCoachUnmetWishes.map((wish) => (
              <UnmetWishRow
                key={wish.wishId}
                wish={wish}
                knownGroupIds={knownGroupIds}
                onTestMove={handleTestMove}
                onChangePriorityOrder={handleChangePriorityOrder}
                // v0.6.0 audit-fix batch C (C14, P2): with exactly one unmet wish, there's no
                // "which one first?" ambiguity a collapsed accordion would otherwise force an extra
                // click through - open it by default so the answer to "what would it take?" is
                // visible immediately.
                defaultOpen={nonCoachUnmetWishes.length === 1}
              />
            ))}
          </Stack>
        )}
      </div>
    </Stack>
  );
}

interface UnmetWishRowProps {
  wish: UnmetWishView;
  /** v0.6.0 F5 review fix (FIX 5, MAJOR): the current run's group ids - see SimpleExplainBody's own
   *  `allGroups` prop doc comment. */
  knownGroupIds: Set<string>;
  onTestMove: (targetGroupId: string) => void;
  onChangePriorityOrder: () => void;
  /** v0.6.0 audit-fix batch C (C14, P2): open the "Vad skulle krävas?" accordion without requiring a
   *  click, when this is the ONLY unmet wish being shown (see SimpleExplainBody's call site). */
  defaultOpen: boolean;
}

function UnmetWishRow({ wish, knownGroupIds, onTestMove, onChangePriorityOrder, defaultOpen }: UnmetWishRowProps) {
  const sensitivity = wish.prioritySensitivity;
  // v0.6.0 F5 review fix (FIX 2, MAJOR): "Ändra prioritetsordning" claims reordering priorities
  // would flip this outcome - offering that CTA without ALSO showing the caution that goes with it
  // (e.g. "men det kan göra en annan spelares placering sämre") overstates the promise, so the CTA
  // is now gated on both the verdict AND a non-empty cautionSv, not the verdict alone.
  const reorderHelps = sensitivity?.available === true && sensitivity.verdict === "FLIPS_BY_REORDER" && !!sensitivity.cautionSv;
  // v0.6.0 F5 review fix (FIX 5, MAJOR): never offer a move into a group the CURRENT run doesn't
  // actually have (a stale cached explanation's candidate from a since-regenerated group set).
  const canTestMove = wish.bestCandidateGroupId != null && knownGroupIds.has(wish.bestCandidateGroupId);

  return (
    <div data-testid="explain-unmet-wish">
      <Text size="sm" fw={600}>
        {wish.wishSv}
      </Text>
      <Text size="sm">{primaryReasonSvForSimple(wish)}</Text>
      {wish.hedgeSv && (
        // v0.6.0 audit-fix batch C (C13, P2): bumped from `size="xs"` to `size="sm"` - parity with
        // primaryReasonSv above, which the hedge directly qualifies (a caveat on the main answer
        // shouldn't read as a smaller-print footnote than the answer itself).
        <Text size="sm" c="dimmed">
          {wish.hedgeSv}
        </Text>
      )}
      {/* v0.6.0 audit-fix batch C (C14, P2, FIX-3 regression): `prioritySensitivity` absent/null
       *  entirely (as opposed to present-but-`available:false`, which DOES still render below with
       *  its own honest `unavailableReasonSv`) means the backend has nothing to say here at all - no
       *  accordion control at all, rather than a control that opens onto an empty/broken panel. */}
      {sensitivity != null && (
        <Accordion variant="separated" mt={4} defaultValue={defaultOpen ? "sensitivity" : null}>
          <Accordion.Item value="sensitivity">
            <Accordion.Control>{sv.results.explain.simple.whatWouldItTakeHeading}</Accordion.Control>
            <Accordion.Panel>
              {sensitivity.available ? (
                sensitivity.summarySv ? (
                  <Stack gap={4}>
                    <Text size="sm">{sensitivity.summarySv}</Text>
                    {/* v0.6.0 F5 review fix (FIX 2, MAJOR): rendered at the SAME size/weight as
                     *  summarySv above (was `size="xs" c="dimmed"`, easy to miss) - a caution
                     *  qualifying the answer just given deserves the same visual weight as that
                     *  answer, not a footnote. */}
                    {sensitivity.cautionSv && <Text size="sm">{sensitivity.cautionSv}</Text>}
                    {(reorderHelps || canTestMove) && (
                      <Group gap="xs" mt={4}>
                        {reorderHelps && (
                          <Button size="compact-xs" variant="light" onClick={onChangePriorityOrder}>
                            {sv.results.explain.simple.changePriorityOrderButton}
                          </Button>
                        )}
                        {canTestMove && (
                          <Button size="compact-xs" variant="light" onClick={() => onTestMove(wish.bestCandidateGroupId!)}>
                            {sv.results.explain.simple.testMoveButton}
                          </Button>
                        )}
                      </Group>
                    )}
                  </Stack>
                ) : (
                  // v0.6.0 F5 review fix (FIX 3, MAJOR): available === true but no summarySv (a
                  // backend classification this component can't further explain) previously rendered
                  // a blank panel - a neutral dimmed sentence instead of silence.
                  <Text size="sm" c="dimmed">
                    {sv.results.explain.simple.sensitivityUnknown}
                  </Text>
                )
              ) : (
                <Text size="sm" c="dimmed">
                  {sensitivity.unavailableReasonSv}
                </Text>
              )}
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      )}
    </div>
  );
}
