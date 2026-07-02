import { useEffect, useState } from "react";
import { Alert, Button, Text } from "@mantine/core";
import { sv } from "../../i18n/sv";
import { hasSeenTutorial, markTutorialSeen } from "./tutorialSeenStore";
import { useTutorialStore } from "./tutorialStore";

/**
 * Startvy's one-time "Ny här?" offer (feature brief: auto-offered ONCE on first app start, via the
 * `gp.tutorial.seen` localStorage flag). Shown at most once ever, regardless of whether the user
 * opens the guide or dismisses the banner - `markTutorialSeen()` fires as soon as it's rendered, not
 * on interaction, since "auto-offered ONCE" means "shown once", not "acted on once".
 */
export function TutorialBanner() {
  const [visible, setVisible] = useState(false);
  const openTutorial = useTutorialStore((state) => state.open);

  useEffect(() => {
    if (!hasSeenTutorial()) {
      markTutorialSeen();
      setVisible(true);
    }
  }, []);

  if (!visible) {
    return null;
  }

  return (
    <Alert
      color="blue"
      variant="light"
      title={sv.tutorial.bannerTitle}
      withCloseButton
      onClose={() => setVisible(false)}
      data-testid="tutorial-banner"
    >
      <Text size="sm" mb="sm">
        {sv.tutorial.bannerBody}
      </Text>
      <Button
        size="xs"
        onClick={() => {
          setVisible(false);
          openTutorial();
        }}
      >
        {sv.tutorial.bannerOpenButton}
      </Button>
    </Alert>
  );
}
