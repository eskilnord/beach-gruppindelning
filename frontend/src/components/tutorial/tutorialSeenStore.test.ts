import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { hasSeenTutorial, markTutorialSeen, resetTutorialSeenForTests } from "./tutorialSeenStore";

describe("tutorialSeenStore", () => {
  beforeEach(() => resetTutorialSeenForTests());
  afterEach(() => resetTutorialSeenForTests());

  it("defaults to not-seen", () => {
    expect(hasSeenTutorial()).toBe(false);
  });

  it("remembers 'seen' after marking it", () => {
    markTutorialSeen();
    expect(hasSeenTutorial()).toBe(true);
  });

  it("is idempotent across repeated marks", () => {
    markTutorialSeen();
    markTutorialSeen();
    expect(hasSeenTutorial()).toBe(true);
  });

  it("resetTutorialSeenForTests reverts back to not-seen", () => {
    markTutorialSeen();
    expect(hasSeenTutorial()).toBe(true);
    resetTutorialSeenForTests();
    expect(hasSeenTutorial()).toBe(false);
  });
});
