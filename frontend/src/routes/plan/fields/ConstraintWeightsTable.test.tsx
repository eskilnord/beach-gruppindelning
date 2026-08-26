import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { ConstraintWeightsTable } from "./ConstraintWeightsTable";
import type { ConstraintDefinition, ConstraintWeightView } from "../../../api/types";

const DEFINITION: ConstraintDefinition = {
  key: "levelBalance",
  label: "Nivåbalans",
  description: "Håller nivåspridningen inom en grupp jämn.",
  constraintCategory: "LEVEL",
  defaultWeight: 100,
  hardOrSoft: "SOFT",
  enabled: true,
};

const WEIGHT_VIEW: ConstraintWeightView = {
  key: "levelBalance",
  label: "Nivåbalans",
  description: "Håller nivåspridningen inom en grupp jämn.",
  constraintCategory: "LEVEL",
  hardOrSoft: "SOFT",
  weight: 100,
  enabled: true,
  overridden: false,
  unit: "PER_POINT",
  direction: "PENALIZE",
};

// The Select's accessible name repeats the row's constraint label (sv.constraintWeights
// .importance.ariaLabel) so ~13 identically-named rows on the real table stay distinguishable.
const BETYDELSE_NIVABALANS = "Betydelse – Nivåbalans";

function mockApis(definitions: ConstraintDefinition[], weights: ConstraintWeightView[]) {
  server.use(
    http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json(weights)),
    http.get("/api/constraint-definitions", () => HttpResponse.json(definitions)),
  );
}

// Mantine's Combobox keeps every dropdown's option list mounted (`keepMounted: true` by default);
// floating-ui's `hide` middleware always concludes the reference is clipped in jsdom (no real
// layout engine, see frontend/src/test/setup.ts) so the listbox stays `display: none` even while
// "open" - its role="option" content is still in the DOM, hence `hidden: true` below (same pattern
// as NewFieldModal.test.tsx/MappingStep.test.tsx). Opening is asynchronous (a render pass wires the
// listbox id onto the input), so this opens the select (if not already open) and waits for
// `aria-controls`.
async function openListbox(user: ReturnType<typeof userEvent.setup>, select: HTMLElement): Promise<HTMLElement> {
  if (!select.getAttribute("aria-controls")) {
    await user.click(select);
  }
  await waitFor(() => expect(select).toHaveAttribute("aria-controls"));
  const listbox = document.getElementById(select.getAttribute("aria-controls")!);
  if (!listbox) {
    throw new Error("Select listbox element not found");
  }
  return listbox;
}

async function selectOption(user: ReturnType<typeof userEvent.setup>, selectName: string, optionName: string) {
  const select = screen.getByRole("textbox", { name: selectName });
  const listbox = await openListbox(user, select);
  await user.click(within(listbox).getByRole("option", { name: optionName, hidden: true }));
}

async function optionTexts(user: ReturnType<typeof userEvent.setup>, selectName: string): Promise<string[]> {
  const select = screen.getByRole("textbox", { name: selectName });
  const listbox = await openListbox(user, select);
  return within(listbox)
    .getAllByRole("option", { hidden: true })
    .map((option) => option.textContent ?? "");
}

/**
 * v0.3.0 WI-3 smoke test: the Konfiguration sub-tab's standard-constraints table gained a
 * section-level HelpTip plus one per Hård/Mjuk, Vikt and Aktiverad column header. Only asserts the
 * HelpTip trigger buttons render (count, not copy) - see HelpTip.test.tsx for the component's own
 * open/close/aria behavior.
 */
describe("ConstraintWeightsTable help tips", () => {
  it("renders a section-level HelpTip and one per explained column header", async () => {
    mockApis([DEFINITION], [WEIGHT_VIEW]);

    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // section heading + Hård/Mjuk + Vikt + Aktiverad column headers = 4
    expect(helpTips.length).toBeGreaterThanOrEqual(4);
  });
});

/**
 * WP4: pedagogical "Betydelse" preset picker + per-row meaning sentence + relative-importance bar,
 * on top of the unchanged underlying weight/enabled semantics. Also covers the adversarial-review
 * fixes: "Egen…" reachability, truthful HARD/disabled wording, and the SOFT-only bar.
 */
describe("ConstraintWeightsTable WP4 pedagogical weights", () => {
  it("snaps a weight equal to the default onto the Normal preset", async () => {
    mockApis([DEFINITION], [WEIGHT_VIEW]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const select = await screen.findByRole("textbox", { name: BETYDELSE_NIVABALANS });
    expect(select).toHaveValue("Normal (100)");
  });

  it("snaps a weight equal to 2x default onto the Viktigare preset", async () => {
    mockApis([DEFINITION], [{ ...WEIGHT_VIEW, weight: 200, overridden: true }]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const select = await screen.findByRole("textbox", { name: BETYDELSE_NIVABALANS });
    expect(select).toHaveValue("Viktigare (200)");
  });

  it("falls back to Egen and shows the NumberInput for an arbitrary weight", async () => {
    mockApis([DEFINITION], [{ ...WEIGHT_VIEW, weight: 73, overridden: true }]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const select = await screen.findByRole("textbox", { name: BETYDELSE_NIVABALANS });
    expect(select).toHaveValue("Egen…");

    const numberInput = screen.getByDisplayValue("73");
    expect(numberInput).toBeVisible();
  });

  it("selecting a preset issues a PUT with the preset's computed weight", async () => {
    const user = userEvent.setup();
    let putBody: unknown;
    server.use(
      http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([WEIGHT_VIEW])),
      http.get("/api/constraint-definitions", () => HttpResponse.json([DEFINITION])),
      http.put("/api/plans/plan-1/constraint-weights", async ({ request }) => {
        putBody = await request.json();
        return HttpResponse.json([{ ...WEIGHT_VIEW, weight: 200, overridden: true }]);
      }),
    );
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByRole("textbox", { name: BETYDELSE_NIVABALANS });
    await selectOption(user, BETYDELSE_NIVABALANS, "Viktigare (200)");

    await waitFor(() => expect(putBody).toEqual([{ key: "levelBalance", weight: 200 }]));
  });

  /**
   * BLOCKER fix (adversarial review): the Select's value used to be derived purely from whether the
   * CURRENT weight snaps onto a preset, so a row sitting exactly on "Normal" (the default state of
   * every row) could never reach "Egen…" - onChange silently ignored the "custom" option. A
   * `customMode` flag now makes it reachable regardless of the current weight.
   */
  it("reaches Egen… from a row that starts on the Normal preset, then commits a typed weight", async () => {
    const user = userEvent.setup();
    let putBody: unknown;
    server.use(
      http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([WEIGHT_VIEW])),
      http.get("/api/constraint-definitions", () => HttpResponse.json([DEFINITION])),
      http.put("/api/plans/plan-1/constraint-weights", async ({ request }) => {
        putBody = await request.json();
        return HttpResponse.json([{ ...WEIGHT_VIEW, weight: 73, overridden: true }]);
      }),
    );
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const select = await screen.findByRole("textbox", { name: BETYDELSE_NIVABALANS });
    expect(select).toHaveValue("Normal (100)");

    await selectOption(user, BETYDELSE_NIVABALANS, "Egen…");
    expect(select).toHaveValue("Egen…");

    // Prefilled with the current weight (100), per spec.
    const numberInput = screen.getByDisplayValue("100");
    expect(numberInput).toBeVisible();

    await user.clear(numberInput);
    await user.type(numberInput, "73");
    await user.tab();

    await waitFor(() => expect(putBody).toEqual([{ key: "levelBalance", weight: 73 }]));
  });

  it("shows the Swedish category header translated from the raw category key", async () => {
    mockApis([DEFINITION], [WEIGHT_VIEW]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.getByText("Nivå")).toBeInTheDocument();
    expect(screen.queryByText("LEVEL")).not.toBeInTheDocument();
  });

  it("falls back to the raw category key when no Swedish translation exists", async () => {
    const def = { ...DEFINITION, constraintCategory: "SOMETHING_NEW" };
    const weight = { ...WEIGHT_VIEW, constraintCategory: "SOMETHING_NEW" };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.getByText("SOMETHING_NEW")).toBeInTheDocument();
  });

  it("does not render a Kategori column - the tinted group-header row carries the category", async () => {
    mockApis([DEFINITION], [WEIGHT_VIEW]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.queryByRole("columnheader", { name: "Kategori" })).not.toBeInTheDocument();
  });

  it("clamps the NumberInput weight to a max of 10000 on blur", async () => {
    const user = userEvent.setup();
    let putBody: unknown;
    server.use(
      http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([{ ...WEIGHT_VIEW, weight: 73, overridden: true }])),
      http.get("/api/constraint-definitions", () => HttpResponse.json([DEFINITION])),
      http.put("/api/plans/plan-1/constraint-weights", async ({ request }) => {
        putBody = await request.json();
        return HttpResponse.json([{ ...WEIGHT_VIEW, weight: 10000, overridden: true }]);
      }),
    );
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const numberInput = await screen.findByDisplayValue("73");
    await user.clear(numberInput);
    await user.type(numberInput, "99999");
    await user.tab();

    // clampBehavior="blur" (Mantine default) clamps the displayed value to `max` on blur, and
    // commitWeight defensively clamps the PUT payload too (see ConstraintWeightsTable.tsx).
    await waitFor(() => expect(numberInput).toHaveValue("10000"));
    await waitFor(() => expect(putBody).toEqual([{ key: "levelBalance", weight: 10000 }]));
  });

  it("dedupes preset options when the default weight is 1, sorted ascending by value", async () => {
    const def = { ...DEFINITION, defaultWeight: 1 };
    const weight = { ...WEIGHT_VIEW, weight: 1 };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const user = userEvent.setup();
    await screen.findByText("Nivåbalans");
    const select = screen.getByRole("textbox", { name: BETYDELSE_NIVABALANS });
    expect(select).toHaveValue("Normal (1)");

    // lessImportant (max(1, round(1/2)) = 1) collapses onto Normal (1) and is deduped away;
    // important (2) and muchMoreImportant (4) stay distinct, and are sorted ascending for display.
    const texts = await optionTexts(user, BETYDELSE_NIVABALANS);
    expect(texts).toEqual(["Normal (1)", "Viktigare (2)", "Mycket viktigare (4)", "Egen…"]);
  });

  it("sorts preset options ascending by weight even when Normal isn't the smallest", async () => {
    const def = { ...DEFINITION, defaultWeight: 1000 };
    const weight = { ...WEIGHT_VIEW, weight: 1000 };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    const user = userEvent.setup();
    await screen.findByText("Nivåbalans");
    // Normal=1000, Mindre viktig=500, Viktigare=2000, Mycket viktigare=4000 - ascending display
    // order puts Mindre viktig first even though Normal is walked first for the dedup tie-break.
    const texts = await optionTexts(user, BETYDELSE_NIVABALANS);
    expect(texts).toEqual(["Mindre viktig (500)", "Normal (1000)", "Viktigare (2000)", "Mycket viktigare (4000)", "Egen…"]);
  });

  it("shows the disabled-meaning sentence instead of the unit sentence for a disabled SOFT row", async () => {
    mockApis([DEFINITION], [{ ...WEIGHT_VIEW, enabled: false, overridden: true }]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.getByText("Avstängd – påverkar inte planen")).toBeInTheDocument();
  });

  it("shows a truthful sentence for an enabled HARD row instead of the false 'Bryts aldrig'", async () => {
    const def = { ...DEFINITION, key: "playerNoOverlap", hardOrSoft: "HARD" as const };
    const weight: ConstraintWeightView = {
      ...WEIGHT_VIEW,
      key: "playerNoOverlap",
      hardOrSoft: "HARD",
      unit: "PER_MATCH",
      direction: "PENALIZE",
    };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Hård regel – går alltid före mjuka regler; överträdelser visas som konflikter.");
    expect(screen.queryByText("Bryts aldrig")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /^Betydelse/ })).not.toBeInTheDocument();
  });

  it("shows the disabled-meaning sentence for a disabled HARD row (some HARD rows ARE disableable)", async () => {
    const def = { ...DEFINITION, key: "coachWishRequired", hardOrSoft: "HARD" as const };
    const weight: ConstraintWeightView = {
      ...WEIGHT_VIEW,
      key: "coachWishRequired",
      hardOrSoft: "HARD",
      enabled: false,
      overridden: true,
      unit: "PER_MATCH",
      direction: "PENALIZE",
    };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.getByText("Avstängd – påverkar inte planen")).toBeInTheDocument();
    // The exact per-row HARD sentence (not the intro card, which also mentions "Hård regel" in a
    // differently-worded sentence without the en dash) must not appear for a disabled row.
    expect(
      screen.queryByText("Hård regel – går alltid före mjuka regler; överträdelser visas som konflikter."),
    ).not.toBeInTheDocument();
  });

  it("shows a dedicated truthful sentence for the MEDIUM waitlist row", async () => {
    const def = { ...DEFINITION, key: "unassignedPlayer", hardOrSoft: "MEDIUM" as const };
    const weight: ConstraintWeightView = {
      ...WEIGHT_VIEW,
      key: "unassignedPlayer",
      hardOrSoft: "MEDIUM",
      weight: 100,
      unit: "PER_POINT",
      direction: "PENALIZE",
    };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText(
      "100 poäng per prioritetspoäng för varje oplacerad spelare – räknas alltid före mjuka regler",
    );
  });

  it("shows a mixed-direction sentence for lateTimeForLowerGroups via the per-key override", async () => {
    const def = { ...DEFINITION, key: "lateTimeForLowerGroups" };
    const weight: ConstraintWeightView = {
      ...WEIGHT_VIEW,
      key: "lateTimeForLowerGroups",
      weight: 30,
      unit: "PER_MATCH",
      direction: "PENALIZE",
    };
    mockApis([def], [weight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("30 poäng – belönar sen tid för lägre grupper och straffar sen tid för toppgrupper");
  });

  it("shows the 10-level-points sentence for levelBalance via the per-key override, not the generic PER_POINT one", async () => {
    // levelBalance's matchWeight is spread units (LevelMath.SPREAD_UNIT_SCALED = 10 level points),
    // not whole level points, since v0.6.0 milestone B2 - the generic PER_POINT_PENALIZE sentence
    // ("per nivåpoäng") would overstate this row's effect by 10x, so it has its own override.
    mockApis([DEFINITION], [WEIGHT_VIEW]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("100 poäng straff per 10 nivåpoängs spridning i en grupp");
    expect(screen.queryByText(/poäng straff per enhet avvikelse/)).not.toBeInTheDocument();
  });

  it("hides the relative-importance bar when every SOFT row is disabled", async () => {
    mockApis([DEFINITION], [{ ...WEIGHT_VIEW, enabled: false, overridden: true }]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("shows the relative-importance bar for an enabled SOFT row but not for the MEDIUM row", async () => {
    const mediumDef = {
      ...DEFINITION,
      key: "unassignedPlayer",
      label: "Oplacerad spelare (kölista)",
      description: "Minimera antal oplacerade spelare, viktat efter prioritet.",
      hardOrSoft: "MEDIUM" as const,
      constraintCategory: "WAITLIST",
    };
    const mediumWeight: ConstraintWeightView = {
      ...WEIGHT_VIEW,
      key: "unassignedPlayer",
      label: "Oplacerad spelare (kölista)",
      description: "Minimera antal oplacerade spelare, viktat efter prioritet.",
      hardOrSoft: "MEDIUM",
      constraintCategory: "WAITLIST",
      unit: "PER_POINT",
      direction: "PENALIZE",
    };
    mockApis([DEFINITION, mediumDef], [WEIGHT_VIEW, mediumWeight]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    await screen.findByText("Oplacerad spelare (kölista)");
    // Exactly one bar: the enabled SOFT row's. The MEDIUM row gets its own dedicated sentence
    // instead (see the test above) since MEDIUM isn't on the same weight scale as SOFT.
    expect(screen.getAllByRole("progressbar")).toHaveLength(1);
  });

  it("renders the intro card explaining relative weights among soft rules, hard-rule precedence, and disabled rows", async () => {
    mockApis([DEFINITION], [WEIGHT_VIEW]);
    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");
    expect(screen.getByText(/Det är bara vikterna i förhållande till varandra/)).toBeInTheDocument();
    expect(screen.queryByText(/bryts aldrig, oavsett vikt/)).not.toBeInTheDocument();
    expect(screen.queryByText(/syns inte heller i förklaringarna/)).not.toBeInTheDocument();
  });
});
