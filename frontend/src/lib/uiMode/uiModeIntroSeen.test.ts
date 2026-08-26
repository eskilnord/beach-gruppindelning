import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { hasSeenUiModeIntro, markUiModeIntroSeen, resetUiModeIntroSeenForTests } from "./uiModeIntroSeen";

describe("uiModeIntroSeen", () => {
  beforeEach(() => resetUiModeIntroSeenForTests());
  afterEach(() => resetUiModeIntroSeenForTests());

  it("defaults to not-seen", () => {
    expect(hasSeenUiModeIntro()).toBe(false);
  });

  it("remembers 'seen' after marking it", () => {
    markUiModeIntroSeen();
    expect(hasSeenUiModeIntro()).toBe(true);
  });

  it("is idempotent across repeated marks", () => {
    markUiModeIntroSeen();
    markUiModeIntroSeen();
    expect(hasSeenUiModeIntro()).toBe(true);
  });

  it("resetUiModeIntroSeenForTests reverts back to not-seen", () => {
    markUiModeIntroSeen();
    expect(hasSeenUiModeIntro()).toBe(true);
    resetUiModeIntroSeenForTests();
    expect(hasSeenUiModeIntro()).toBe(false);
  });
});
