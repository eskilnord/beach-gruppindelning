import { useEffect, useState } from "react";
import { Alert, Button, Group, Text } from "@mantine/core";
import { sv } from "../../i18n/sv";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { hasSeenUiModeIntro, markUiModeIntroSeen } from "../../lib/uiMode/uiModeIntroSeen";
import { useUiModeStore } from "../../lib/uiMode/uiModeStore";
import { useConfirmedAdvancedMode } from "./useConfirmedAdvancedMode";

interface UiModeIntroBannerProps {
  /** v0.6.0 audit-fix A2: this must NEVER show on a fresh install (nobody's used the app yet, so
   *  there's nothing to contrast "the simpler mode" against) - gated on evidence of prior use, i.e.
   *  at least one season already existing. Threaded in from StartPage's own already-fetched
   *  `useSeasons()` query (not a second one here) - `undefined` while that query hasn't resolved
   *  yet, treated the same as "no evidence yet" (don't show). */
  hasSeasons: boolean | undefined;
  /** v0.6.0 audit-fix A2(d): true when StartPage's TutorialBanner is ALSO about to show this same
   *  render (both are one-time Startvy notices - showing both at once is noisy, and the tutorial
   *  offer is the more foundational of the two). Deliberately DEFERS rather than burning the
   *  `gp.uiMode.introSeen` flag - see StartPage.tsx's doc comment on how this is computed race-free
   *  (independent of TutorialBanner's own internal effect timing). */
  deferForTutorial: boolean;
}

/**
 * v0.6.0 F6 (M-S6): Startvy's one-time "the app now has a simpler mode" notice - shown at most once
 * ever (the `gp.uiMode.introSeen` localStorage flag, mirrors TutorialBanner.tsx's own
 * `gp.tutorial.seen` pattern exactly), and only to a user who's actually IN SIMPLE mode (an
 * already-advanced user needs no introduction to a mode they're not in - if they later switch down
 * to SIMPLE for the first time, they get the intro then).
 *
 * v0.6.0 audit-fix A2: (a) additionally gated on `hasSeasons` (never shown on a fresh install - see
 * that prop's own doc comment); (b) reworded to name what's actually different instead of just
 * announcing a mode exists; (c) "Visa alla inställningar (avancerat läge)" now routes through the
 * SAME confirm modal as UiModeSwitch/AdvancedRouteGate (useConfirmedAdvancedMode) instead of
 * flipping the mode directly - choosing it from this banner is no longer treated as its own
 * confirmation; (d) deferred (not shown, flag not burned) while TutorialBanner is also showing.
 *
 * v0.6.0 F6 review fix (FIX 3, MAJOR): both showing the banner AND burning the `introSeen` flag are
 * deferred until UiModeSync's backend reconcile has settled (`useUiModeStore`'s `reconciled`, set by
 * `markUiModeReconciled` on either a successful GET or a GET error - see that flag's own doc
 * comment). A user might boot into SIMPLE only because the local mirror is stale and the backend's
 * durable value is actually ADVANCED; showing the notice (and burning the once-ever flag) before that
 * reconcile has had a chance to run would show a SIMPLE-mode notice to what's really an
 * ADVANCED-mode user, or permanently suppress it for a user who never actually got to see it. Also
 * re-checks `isSimple` on every render (not just inside the one-time effect) so a mode flip away from
 * SIMPLE - the reconcile itself, or the admin's own toggle - hides an already-visible banner live.
 */
export function UiModeIntroBanner({ hasSeasons, deferForTutorial }: UiModeIntroBannerProps) {
  const isSimple = useIsSimpleMode();
  const reconciled = useUiModeStore((state) => state.reconciled);
  const { requestAdvancedMode, confirmModal } = useConfirmedAdvancedMode();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (deferForTutorial) {
      return;
    }
    if (reconciled && isSimple && hasSeasons && !hasSeenUiModeIntro()) {
      markUiModeIntroSeen();
      setVisible(true);
    }
  }, [reconciled, isSimple, hasSeasons, deferForTutorial]);

  // v0.6.0 audit-fix A2(c): `confirmModal` is rendered unconditionally below (NOT inside this
  // early-return's gate) - dismissing the banner (`setVisible(false)`) must not unmount the confirm
  // modal instance out from under a click that just opened it via `requestAdvancedMode()`.
  const showAlert = visible && isSimple;

  return (
    <>
      {showAlert && (
        <Alert
          color="blue"
          variant="light"
          title={sv.uiMode.intro.title}
          withCloseButton
          onClose={() => setVisible(false)}
          data-testid="ui-mode-intro-banner"
        >
          <Text size="sm" mb="sm">
            {sv.uiMode.intro.body}
          </Text>
          <Group gap="xs">
            <Button size="xs" onClick={() => setVisible(false)} data-testid="ui-mode-intro-ok">
              {sv.uiMode.intro.okButton}
            </Button>
            <Button
              size="xs"
              variant="default"
              data-testid="ui-mode-intro-keep-advanced"
              onClick={() => {
                setVisible(false);
                requestAdvancedMode();
              }}
            >
              {sv.uiMode.intro.keepAdvancedButton}
            </Button>
          </Group>
        </Alert>
      )}
      {confirmModal}
    </>
  );
}
