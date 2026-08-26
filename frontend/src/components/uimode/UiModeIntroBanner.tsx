import { useEffect, useState } from "react";
import { Alert, Button, Group, Text } from "@mantine/core";
import { sv } from "../../i18n/sv";
import { useIsSimpleMode, useUiMode } from "../../lib/uiMode/useUiMode";
import { hasSeenUiModeIntro, markUiModeIntroSeen } from "../../lib/uiMode/uiModeIntroSeen";
import { useUiModeStore } from "../../lib/uiMode/uiModeStore";

/**
 * v0.6.0 F6 (M-S6): Startvy's one-time "the app now has a simpler mode" notice - shown at most once
 * ever (the `gp.uiMode.introSeen` localStorage flag, mirrors TutorialBanner.tsx's own
 * `gp.tutorial.seen` pattern exactly), and only to a user who's actually IN SIMPLE mode (an
 * already-advanced user needs no introduction to a mode they're not in - if they later switch down
 * to SIMPLE for the first time, they get the intro then).
 *
 * "Behåll avancerat läge" is a deliberate, explicit opt-out: it flips the mode straight to ADVANCED
 * WITHOUT UiModeSwitch's confirm modal - choosing this labeled action from this banner already IS
 * the confirmation UiModeSwitch's modal would otherwise ask for.
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
export function UiModeIntroBanner() {
  const isSimple = useIsSimpleMode();
  const reconciled = useUiModeStore((state) => state.reconciled);
  const { setMode } = useUiMode();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (reconciled && isSimple && !hasSeenUiModeIntro()) {
      markUiModeIntroSeen();
      setVisible(true);
    }
  }, [reconciled, isSimple]);

  if (!visible || !isSimple) {
    return null;
  }

  return (
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
            setMode("ADVANCED");
            setVisible(false);
          }}
        >
          {sv.uiMode.intro.keepAdvancedButton}
        </Button>
      </Group>
    </Alert>
  );
}
