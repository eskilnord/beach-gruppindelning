import { describe, expect, it } from "vitest";
import { sv } from "../../../i18n/sv";
import { describeCoachShortage, describeWaitlistRisk } from "./riskBanner";

describe("describeWaitlistRisk", () => {
  it("maps NONE to green with the none headline", () => {
    const banner = describeWaitlistRisk("NONE", "Kapacitet räcker till alla anmälda");
    expect(banner.color).toBe("green");
    expect(banner.title).toBe(sv.capacity.risk.none);
    expect(banner.message).toBe("Kapacitet räcker till alla anmälda");
  });

  it("maps OVER_TARGET to yellow (spec §12.4 worked example: fits max, exceeds target)", () => {
    const banner = describeWaitlistRisk("OVER_TARGET", "Möjligt, men grupperna blir större än target");
    expect(banner.color).toBe("yellow");
    expect(banner.title).toBe(sv.capacity.risk.overTarget);
    expect(banner.message).toBe("Möjligt, men grupperna blir större än target");
  });

  it("maps OVER_MAX to red", () => {
    const banner = describeWaitlistRisk("OVER_MAX", "Fler anmälda än maxkapacitet — kölista krävs");
    expect(banner.color).toBe("red");
    expect(banner.title).toBe(sv.capacity.risk.overMax);
  });

  it("maps UNKNOWN to a neutral gray state", () => {
    const banner = describeWaitlistRisk("UNKNOWN", "Standardstorlekar (target/max) saknas för planen");
    expect(banner.color).toBe("gray");
    expect(banner.title).toBe(sv.capacity.risk.unknown);
  });

  it("falls back to gray for an undefined/unrecognized risk value", () => {
    expect(describeWaitlistRisk(undefined, undefined).color).toBe("gray");
    expect(describeWaitlistRisk("SOMETHING_NEW" as never, "x").color).toBe("gray");
  });

  it("passes the backend's Swedish message through verbatim rather than re-deriving it", () => {
    const banner = describeWaitlistRisk("OVER_MAX", "En helt annan text");
    expect(banner.message).toBe("En helt annan text");
  });
});

describe("describeCoachShortage", () => {
  it("maps shortageRisk=true to red with the shortage headline", () => {
    const banner = describeCoachShortage(true, "Risk för tränarbrist: för få tillgängliga tränare vid: X");
    expect(banner.color).toBe("red");
    expect(banner.title).toBe(sv.capacity.coachShortage.risk);
    expect(banner.message).toContain("tränarbrist");
  });

  it("maps shortageRisk=false to green with the ok headline", () => {
    const banner = describeCoachShortage(false, "Tillräckligt med tränare för samtliga grupper");
    expect(banner.color).toBe("green");
    expect(banner.title).toBe(sv.capacity.coachShortage.ok);
  });
});
