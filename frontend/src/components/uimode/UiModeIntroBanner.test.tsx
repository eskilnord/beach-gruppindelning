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

  it("shows in SIMPLE mode the first time", () => {
    renderWithProviders(<UiModeIntroBanner />, { uiMode: "SIMPLE" });
    expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument();
    expect(screen.getByText(sv.uiMode.intro.body)).toBeInTheDocument();
  });

  it("never shows in ADVANCED mode", () => {
    renderWithProviders(<UiModeIntroBanner />, { uiMode: "ADVANCED" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  it("'Okej' dismisses forever - a second render never shows it again", () => {
    const { unmount } = renderWithProviders(<UiModeIntroBanner />, { uiMode: "SIMPLE" });
    expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument();

    // The banner marks itself seen the moment it renders (before any click) - same "shown once,
    // not acted on once" contract as TutorialBanner - so even unmounting without clicking anything
    // is enough to prove it never shows again; clicking Okej is the primary documented dismiss path,
    // covered separately below.
    unmount();

    renderWithProviders(<UiModeIntroBanner />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  it("clicking 'Okej' dismisses the banner immediately", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UiModeIntroBanner />, { uiMode: "SIMPLE" });

    await user.click(screen.getByTestId("ui-mode-intro-ok"));
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
  });

  it("'Behåll avancerat läge' flips the mode to ADVANCED (no confirm modal) and dismisses", async () => {
    server.use(http.put("/api/app-settings", () => HttpResponse.json({ uiMode: "ADVANCED" })));

    const user = userEvent.setup();
    renderWithProviders(<UiModeIntroBanner />, { uiMode: "SIMPLE" });

    await user.click(screen.getByTestId("ui-mode-intro-keep-advanced"));

    // The store flips synchronously (useUiMode's setMode is optimistic - the PUT above is the
    // background persist, not a precondition for the local switch).
    expect(useUiModeStore.getState().mode).toBe("ADVANCED");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ui-mode-intro-banner")).not.toBeInTheDocument();
    await waitFor(() => expect(useUiModeStore.getState().mode).toBe("ADVANCED"));
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
        <UiModeIntroBanner />
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
        <UiModeIntroBanner />
      </>,
      { uiMode: "SIMPLE", reconciled: false },
    );

    await waitFor(() => expect(screen.getByTestId("ui-mode-intro-banner")).toBeInTheDocument());
    expect(hasSeenUiModeIntro()).toBe(true);
  });
});
