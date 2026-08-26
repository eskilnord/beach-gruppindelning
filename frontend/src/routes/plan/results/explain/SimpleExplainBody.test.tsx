import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../../../test/renderWithProviders";
import { sv } from "../../../../i18n/sv";
import type { FactorView, PersonExplanationResponse, UnmetWishView } from "../../../../api/types";
import type { GroupOption } from "./ExplainDrawer";
import { SimpleExplainBody } from "./SimpleExplainBody";

const navigateMock = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => navigateMock };
});

const BASE: PersonExplanationResponse = {
  runId: "run-1",
  basedOnRevision: 3,
  currentRevision: 3,
  stale: false,
  participantProfileId: "participant-1",
  name: "Karin Lindqvist",
  selectedGroup: {
    groupId: "group-1",
    name: "Grupp A",
    size: 8,
    targetSize: 8,
    maxSize: 10,
    timeLabelSv: "Torsdag 18.00–19.30 / Bana 1",
  },
  positiveFactors: [],
  negativeFactors: [],
  brokenWishes: [],
  appliedWeights: [
    { key: "friendWish", label: "Kompisönskemål", level: "SOFT", weight: 60 },
    { key: "levelBalance", label: "Nivåbalans", level: "SOFT", weight: 100 },
  ],
  alternatives: [],
  indirectFactors: [],
  placementSummarySv: "Karin placerades i Grupp A eftersom nivån matchar gruppens snitt.",
  lockedNoticeSv: undefined,
  unmetWishes: [],
};

function unmetWish(overrides: Partial<UnmetWishView> = {}): UnmetWishView {
  return {
    wishId: "wish-1",
    key: "friendWish",
    bucket: "SOFT",
    wishSv: "Vill spela med Erik Eriksson",
    outcome: "TRADE_OFF",
    primaryReasonSv: "Erik placerades i en annan grupp för att jämna ut nivåerna.",
    hedgeSv: undefined,
    candidateGroupIds: [],
    bestCandidateGroupId: undefined,
    bestCandidateDelta: undefined,
    competingReasons: [],
    prioritySensitivity: undefined,
    ...overrides,
  };
}

/** The current run's groups (ExplainDrawer's own `allGroups` prop) - includes "group-2" since
 *  several tests below point `bestCandidateGroupId` at it (FIX 5's own "known group" gate). */
const ALL_GROUPS: GroupOption[] = [
  { id: "group-1", name: "Grupp A" },
  { id: "group-2", name: "Grupp B" },
];

function renderBody(data: PersonExplanationResponse, onTestMove = vi.fn(), allGroups: GroupOption[] = ALL_GROUPS) {
  renderWithProviders(
    <SimpleExplainBody planId="plan-1" data={data} allGroups={allGroups} onTestMove={onTestMove} />,
    { uiMode: "SIMPLE" },
  );
  return { onTestMove };
}

describe("SimpleExplainBody", () => {
  it("renders the headline, placement narrative, and positive factors", () => {
    renderBody({
      ...BASE,
      positiveFactors: [{ messageSv: "Kompisönskemål med Lisa uppfylldes" }],
    });

    expect(screen.getByTestId("explain-why-headline")).toHaveTextContent(
      "Karin Lindqvist tränar i Grupp A (Torsdag 18.00–19.30 / Bana 1)",
    );
    expect(screen.getByText(BASE.placementSummarySv)).toBeInTheDocument();
    expect(screen.getByText("Kompisönskemål med Lisa uppfylldes", { exact: false })).toBeInTheDocument();
  });

  it("omits the trailing parenthetical when the selected group has no timeLabelSv", () => {
    renderBody({ ...BASE, selectedGroup: { ...BASE.selectedGroup!, timeLabelSv: undefined } });

    expect(screen.getByTestId("explain-why-headline")).toHaveTextContent("Karin Lindqvist tränar i Grupp A");
    expect(screen.getByTestId("explain-why-headline")).not.toHaveTextContent("(");
  });

  it("renders the waitlist narrative instead of a headline when selectedGroup is null", () => {
    renderBody({
      ...BASE,
      selectedGroup: undefined,
      waitlist: { reasonSv: "Ingen grupp hade plats kvar.", perGroupBlockers: [] },
    });

    expect(screen.queryByTestId("explain-why-headline")).not.toBeInTheDocument();
    expect(screen.getByTestId("explain-waitlist-narrative")).toHaveTextContent("Ingen grupp hade plats kvar.");
    // v0.6.0 F5 review fix (minor, waitlist heading level): substitutes for the (order-4) headline
    // slot here, so its own heading must be order 4 too, not the order-5 it defaults to as an
    // embedded ADVANCED section.
    expect(screen.getByRole("heading", { level: 4, name: sv.results.waitlist.heading })).toBeInTheDocument();
  });

  it("collapses positive factors beyond 3 behind a 'Visa fler' anchor", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      positiveFactors: [
        { messageSv: "Faktor 1" },
        { messageSv: "Faktor 2" },
        { messageSv: "Faktor 3" },
        { messageSv: "Faktor 4" },
      ],
    });

    expect(screen.getByText("Faktor 1", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("Faktor 3", { exact: false })).toBeInTheDocument();
    expect(screen.queryByText("Faktor 4", { exact: false })).not.toBeInTheDocument();

    await user.click(screen.getByText(sv.results.explain.simple.showMoreFactors));
    expect(screen.getByText("Faktor 4", { exact: false })).toBeInTheDocument();
  });

  it("shows the locked notice Alert only when lockedNoticeSv is present", () => {
    const { rerender } = renderWithProviders(
      <SimpleExplainBody planId="plan-1" data={BASE} allGroups={ALL_GROUPS} onTestMove={vi.fn()} />,
      { uiMode: "SIMPLE" },
    );
    expect(screen.queryByTestId("explain-locked-notice")).not.toBeInTheDocument();

    rerender(
      <SimpleExplainBody
        planId="plan-1"
        data={{ ...BASE, lockedNoticeSv: "Karin är låst i Grupp A." }}
        allGroups={ALL_GROUPS}
        onTestMove={vi.fn()}
      />,
    );
    expect(screen.getByTestId("explain-locked-notice")).toHaveTextContent("Karin är låst i Grupp A.");
  });

  it("does not render a 'Tillämpade vikter' weights table (ADVANCED-only)", () => {
    renderBody(BASE);
    expect(screen.queryByText(sv.results.explain.appliedWeightsHeading)).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows the 'no unmet wishes' state when unmetWishes is empty", () => {
    renderBody({ ...BASE, unmetWishes: [] });
    expect(screen.getByText(sv.results.explain.simple.noUnmetWishes)).toBeInTheDocument();
    expect(screen.queryByTestId("explain-unmet-wish")).not.toBeInTheDocument();
  });

  it("renders one row per unmet wish with wishSv/primaryReasonSv/hedgeSv", () => {
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({ wishId: "w1", hedgeSv: "Det kan ändras vid en omkörning." }),
        unmetWish({ wishId: "w2", wishSv: "Vill spela på torsdagar" }),
      ],
    });

    const rows = screen.getAllByTestId("explain-unmet-wish");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Vill spela med Erik Eriksson");
    expect(rows[0]).toHaveTextContent("Erik placerades i en annan grupp för att jämna ut nivåerna.");
    expect(rows[0]).toHaveTextContent("Det kan ändras vid en omkörning.");
    expect(rows[1]).toHaveTextContent("Vill spela på torsdagar");
  });

  it("expanding 'Vad skulle krävas?' performs no network fetch - the data is already present", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: { available: true, summarySv: "Om prioriteten ändras kan Erik hamna i samma grupp." },
        }),
      ],
    });
    fetchSpy.mockClear();

    await user.click(screen.getByRole("button", { name: sv.results.explain.simple.whatWouldItTakeHeading }));

    expect(await screen.findByText("Om prioriteten ändras kan Erik hamna i samma grupp.")).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("prioritySensitivity.available with a summarySv renders the summary/caution and no CTA when neither applies", () => {
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: {
            available: true,
            summarySv: "Ingen omprioritering skulle ändra utfallet.",
            cautionSv: "Andra spelare kan då hamna sämre till.",
          },
        }),
      ],
    });

    expect(screen.getByText("Ingen omprioritering skulle ändra utfallet.")).toBeInTheDocument();
    expect(screen.getByText("Andra spelare kan då hamna sämre till.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.testMoveButton })).not.toBeInTheDocument();
  });

  it("prioritySensitivity.available === false renders unavailableReasonSv verbatim, no CTAs", () => {
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: { available: false, unavailableReasonSv: "Önskemålet gäller en låst placering." },
        }),
      ],
    });

    expect(screen.getByText("Önskemålet gäller en låst placering.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.testMoveButton })).not.toBeInTheDocument();
  });

  /** The CTA buttons live inside the Accordion.Panel, which Mantine keeps mounted-but-`display:none`
   *  while collapsed (see use-collapse.mjs's `getCollapsedStyles`) - invisible to `getByRole`'s
   *  default accessibility filtering, so every test that queries a CTA by role must open the panel
   *  first (same as a real user would). */
  async function openWhatWouldItTake(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: sv.results.explain.simple.whatWouldItTakeHeading }));
  }

  it("shows 'Ändra prioritetsordning' only when verdict is FLIPS_BY_REORDER AND a caution is present, and navigates to prioriteringar", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: {
            available: true,
            summarySv: "Att flytta upp önskemålet skulle ge Erik samma grupp.",
            verdict: "FLIPS_BY_REORDER",
            // v0.6.0 F5 review fix (FIX 2, MAJOR): the CTA now requires a caution alongside the
            // verdict (see the withheld-without-caution test below) - this test used to omit
            // cautionSv entirely and still expect the button, encoding the pre-fix behavior as the
            // happy path.
            cautionSv: "Andra spelare kan då hamna sämre till.",
          },
        }),
      ],
    });

    await openWhatWouldItTake(user);
    const button = await screen.findByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton });
    await user.click(button);
    expect(navigateMock).toHaveBeenCalledWith("/plans/plan-1/prioriteringar");
  });

  it("withholds 'Ändra prioritetsordning' when verdict is FLIPS_BY_REORDER but cautionSv is absent", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: {
            available: true,
            summarySv: "Att flytta upp önskemålet skulle ge Erik samma grupp.",
            verdict: "FLIPS_BY_REORDER",
            cautionSv: undefined,
          },
        }),
      ],
    });

    await openWhatWouldItTake(user);
    expect(await screen.findByText("Att flytta upp önskemålet skulle ge Erik samma grupp.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton })).not.toBeInTheDocument();
  });

  it("hides 'Ändra prioritetsordning' when verdict is anything other than FLIPS_BY_REORDER", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          prioritySensitivity: {
            available: true,
            summarySv: "Ingen ändring av prioritetsordningen hjälper här.",
            verdict: "NO_ORDER_HELPS",
          },
        }),
      ],
    });

    await openWhatWouldItTake(user);
    expect(await screen.findByText("Ingen ändring av prioritetsordningen hjälper här.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton })).not.toBeInTheDocument();
  });

  it("shows 'Testa att flytta' only when bestCandidateGroupId is present, and opens WhatIfDialog prefilled", async () => {
    const user = userEvent.setup();
    const { onTestMove } = renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          bestCandidateGroupId: "group-2",
          prioritySensitivity: { available: true, summarySv: "En flytt till Grupp B löser det." },
        }),
      ],
    });

    await openWhatWouldItTake(user);
    const button = await screen.findByRole("button", { name: sv.results.explain.simple.testMoveButton });
    await user.click(button);
    expect(onTestMove).toHaveBeenCalledWith("participant-1", "Karin Lindqvist", "group-1", "group-2");
  });

  it("hides 'Testa att flytta' when bestCandidateGroupId is absent", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [
        unmetWish({
          bestCandidateGroupId: undefined,
          prioritySensitivity: { available: true, summarySv: "Ingen kandidatgrupp hittades." },
        }),
      ],
    });

    await openWhatWouldItTake(user);
    expect(await screen.findByText("Ingen kandidatgrupp hittades.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.testMoveButton })).not.toBeInTheDocument();
  });

  // v0.6.0 F5 review fix (FIX 5, MAJOR): a wish's `bestCandidateGroupId` naming a group that isn't
  // among the CURRENT run's groups (a stale cached explanation from before a re-solve regenerated
  // groups) must never offer "Testa att flytta" into it.
  it("hides 'Testa att flytta' when bestCandidateGroupId isn't among the current run's groups", async () => {
    const user = userEvent.setup();
    renderBody(
      {
        ...BASE,
        unmetWishes: [
          unmetWish({
            bestCandidateGroupId: "group-stale",
            prioritySensitivity: { available: true, summarySv: "En flytt till en annan grupp löser det." },
          }),
        ],
      },
      vi.fn(),
      ALL_GROUPS,
    );

    await openWhatWouldItTake(user);
    expect(await screen.findByText("En flytt till en annan grupp löser det.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.testMoveButton })).not.toBeInTheDocument();
  });

  // v0.6.0 F5 review fix (FIX 3, MAJOR): `available: true` with no `summarySv` (a backend
  // classification this component can't further explain) used to render a blank accordion panel.
  it("renders a neutral fallback sentence when prioritySensitivity.available is true but summarySv is absent", async () => {
    const user = userEvent.setup();
    renderBody({
      ...BASE,
      unmetWishes: [unmetWish({ prioritySensitivity: { available: true, summarySv: undefined } })],
    });

    await openWhatWouldItTake(user);
    expect(await screen.findByText(sv.results.explain.simple.sensitivityUnknown)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.changePriorityOrderButton })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.results.explain.simple.testMoveButton })).not.toBeInTheDocument();
  });

  // v0.6.0 F5 review fix (FIX 1, BLOCKER): the coach-hiding sweep's own ExplainDrawer row - a coach's
  // name must never leak through SIMPLE's positive factors or unmet-wishes list.
  describe("coach info never leaks into SIMPLE (FIX 1)", () => {
    const COACH_NAME = "Anna Tränare";

    function coachPositiveFactor(): FactorView {
      return { messageSv: `Karin Lindqvist fick önskad tränare ${COACH_NAME}` };
    }

    function coachUnmetWish(): UnmetWishView {
      return unmetWish({
        wishId: "COACH:person-9",
        key: "coachWishSoft",
        wishSv: `Karin Lindqvist vill helst ha tränare ${COACH_NAME}`,
        primaryReasonSv: `${COACH_NAME} coachar en annan grupp.`,
      });
    }

    it("drops a coach-wish positive factor from the (otherwise non-empty) positive-factors list", () => {
      renderBody({
        ...BASE,
        positiveFactors: [{ messageSv: "Kompisönskemål med Lisa uppfylldes" }, coachPositiveFactor()],
      });

      expect(screen.getByText("Kompisönskemål med Lisa uppfylldes", { exact: false })).toBeInTheDocument();
      expect(screen.queryByText(COACH_NAME, { exact: false })).not.toBeInTheDocument();
    });

    it("drops a COACH: unmet wish entirely, falling back to the empty state when it's the only one", () => {
      renderBody({ ...BASE, unmetWishes: [coachUnmetWish()] });

      expect(screen.queryByTestId("explain-unmet-wish")).not.toBeInTheDocument();
      expect(screen.queryByText(COACH_NAME, { exact: false })).not.toBeInTheDocument();
      expect(screen.getByText(sv.results.explain.simple.noUnmetWishes)).toBeInTheDocument();
    });

    it("keeps a non-coach unmet wish alongside a filtered-out COACH: one", () => {
      renderBody({
        ...BASE,
        unmetWishes: [coachUnmetWish(), unmetWish({ wishId: "FRIEND:person-3" })],
      });

      const rows = screen.getAllByTestId("explain-unmet-wish");
      expect(rows).toHaveLength(1);
      expect(rows[0]).toHaveTextContent("Vill spela med Erik Eriksson");
      expect(screen.queryByText(COACH_NAME, { exact: false })).not.toBeInTheDocument();
    });
  });
});
