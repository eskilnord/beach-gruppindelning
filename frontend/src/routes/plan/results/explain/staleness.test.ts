import { describe, expect, it } from "vitest";
import { sv } from "../../../../i18n/sv";
import { describeStaleness } from "./staleness";

describe("describeStaleness", () => {
  it("hides the banner when the explanation is not stale", () => {
    const banner = describeStaleness(false);
    expect(banner.show).toBe(false);
    expect(banner.message).toBe("");
  });

  it("shows the banner with the honest re-computed-from-current-state wording when stale", () => {
    const banner = describeStaleness(true);
    expect(banner.show).toBe(true);
    expect(banner.message).toBe(sv.results.explain.staleBanner);
    expect(banner.message).toContain("Planen har ändrats efter denna körning");
    expect(banner.message).toContain("speglar nuvarande läge");
    expect(banner.message).toContain("Kör om optimeringen");
  });

  it("does not claim the content is based on the old run's data (M7-review truthfulness fix)", () => {
    // A stale explanation is RE-COMPUTED from the plan's CURRENT persisted state, never replayed
    // from the old run's snapshot - the banner must not imply otherwise.
    const banner = describeStaleness(true);
    expect(banner.message).not.toContain("baserad på körning");
    expect(banner.message).not.toContain("Inaktuell");
  });
});
