import { afterEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { useUiModeStore } from "../../lib/uiMode/uiModeStore";
import { hasSeenUiModeIntro, resetUiModeIntroSeenForTests } from "../../lib/uiMode/uiModeIntroSeen";
import { UiModeIntroBanner } from "./UiModeIntroBanner";
import { UiModeSync } from "./UiModeSync";

describe("UiModeIntroBanner", () => {
  afterEach(() => resetUiModeIntroSeenForTests());

  it("shows in SIMPLE mode the first time, once evidence of prior use exists (hasSeasons=true)", () => {
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "SIMPLE" });
    expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument();
    expect(screen.getByText(sv.uiMode.intro.body)).toBeInTheDocument();
  });

  it("never shows in ADVANCED mode", () => {
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "ADVANCED" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  // v0.6.0 audit-fix A2(a): never shown on a fresh install - gated on evidence of prior use.
  it("never shows on a fresh install (hasSeasons=false), even in SIMPLE mode", () => {
    renderWithProviders(<UiModeIntroBanner hasSeasons={false} deferForTutorial={false} />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  it("does not show (and does not burn the flag) while hasSeasons is still undefined (seasons query not resolved yet)", () => {
    renderWithProviders(<UiModeIntroBanner hasSeasons={undefined} deferForTutorial={false} />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
    expect(hasSeenUiModeIntro()).toBe(false);
  });

  // v0.6.0 audit-fix A2(d): defers (doesn't burn the flag) while TutorialBanner is also showing.
  it("defers without burning the flag while TutorialBanner is showing", () => {
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={true} />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
    expect(hasSeenUiModeIntro()).toBe(false);
  });

  it("'Okej' dismisses forever - a second render never shows it again", () => {
    const { unmount } = renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, {
      uiMode: "SIMPLE",
    });
    expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument();

    // The banner marks itself seen the moment it renders (before any click) - same "shown once,
    // not acted on once" contract as TutorialBanner - so even unmounting without clicking anything
    // is enough to prove it never shows again; clicking Okej is the primary documented dismiss path,
    // covered separately below.
    unmount();

    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  it("clicking 'Okej' dismisses the banner immediately", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "SIMPLE" });

    await user.click(screen.getByTestId("ui-mode-intro-ok"));
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  // v0.6.0 audit-fix A2(c): "Visa alla inställningar (avancerat läge)" now routes through the same
  // confirm modal as UiModeSwitch/AdvancedRouteGate instead of flipping the mode directly.
  it("'Visa alla inställningar (avancerat läge)' opens the confirm modal; confirming flips the mode to ADVANCED and dismisses", async () => {
    server.use(http.put("/api/app-settings", () => HttpResponse.json({ uiMode: "ADVANCED" })));

    const user = userEvent.setup();
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "SIMPLE" });

    await user.click(screen.getByTestId("ui-mode-intro-keep-advanced"));
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();

    await screen.findByRole("dialog", { name: sv.uiMode.enableConfirm.title });
    await user.click(screen.getByTestId("confirmed-advanced-mode-confirm"));

    await waitFor(() => expect(useUiModeStore.getState().mode).toBe("ADVANCED"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: sv.uiMode.enableConfirm.title })).not.toBeInTheDocument(),
    );
  });

  it("cancelling the confirm modal from the banner keeps SIMPLE mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UiModeIntroBanner hasSeasons deferForTutorial={false} />, { uiMode: "SIMPLE" });

    await user.click(screen.getByTestId("ui-mode-intro-keep-advanced"));
    await screen.findByRole("dialog", { name: sv.uiMode.enableConfirm.title });
    await user.click(screen.getByRole("button", { name: sv.uiMode.enableConfirm.cancel }));

    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: sv.uiMode.enableConfirm.title })).not.toBeInTheDocument(),
    );
    expect(useUiModeStore.getState().mode).toBe("SIMPLE");
  });

  // v0.6.0 F6 review fix (FIX 3, MAJOR): a user who boots into SIMPLE only because the local mirror
  // is stale (the backend's durable value is really ADVANCED) must never see the intro banner AT
  // ALL, and the once-ever `introSeen` flag must never get burned for them either - both are deferred
  // until UiModeSync's reconcile has settled (`reconciled: false` here, mirroring a fresh session
  // that hasn't reconciled yet).
  it("never shows (and never burns the seen flag) when the backend reconcile settles to ADVANCED before the banner had a chance to appear", async () => {
    server.use(
      http.get("/api/app-settings", async () => {
        await delay(80);
        return HttpResponse.json({ uiMode: "ADVANCED" });
      }),
    );

    renderWithProviders(
      <>
        <UiModeSync />
        <UiModeIntroBanner hasSeasons deferForTutorial={false} />
      </>,
      { uiMode: "SIMPLE", reconciled: false },
    );

    // Before the (slow) GET settles, the banner must not have shown yet.
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
    expect(hasSeenUiModeIntro()).toBe(false);

    await waitFor(() => expect(useUiModeStore.getState().reconciled).toBe(true));
    await waitFor(() => expect(useUiModeStore.getState().mode).toBe("ADVANCED"));

    // The reconcile flipped the mode to ADVANCED before the banner ever got the chance to show - it
    // must never show, and the "seen" flag must never get burned for a user who was never shown it.
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
    expect(hasSeenUiModeIntro()).toBe(false);
  });

  // v0.6.0 F6 review fix (FIX 3, MAJOR): an unreachable backend (GET error) must not suppress the
  // banner forever - `markUiModeReconciled` (UiModeSync.tsx) fires on a GET error too.
  it("shows once the backend reconcile settles via a GET error (unreachable backend)", async () => {
    server.use(http.get("/api/app-settings", () => HttpResponse.error()));

    renderWithProviders(
      <>
        <UiModeSync />
        <UiModeIntroBanner hasSeasons deferForTutorial={false} />
      </>,
      { uiMode: "SIMPLE", reconciled: false },
    );

    await waitFor(() => expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument());
    expect(hasSeenUiModeIntro()).toBe(true);
  });
});
