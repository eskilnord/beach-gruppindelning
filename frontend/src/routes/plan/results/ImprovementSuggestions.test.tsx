import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { filterSuggestionsForUiMode, ImprovementSuggestions } from "./ImprovementSuggestions";
import type { ImprovementSuggestionsResponse, SuggestionView } from "../../../api/types";

const SUGGESTIONS_URL = "/api/plans/plan-1/runs/run-1/suggestions";

const BASE_RESPONSE: ImprovementSuggestionsResponse = {
  runId: "run-1",
  basedOnRevision: 3,
  currentRevision: 3,
  stale: false,
  omittedCount: 0,
  suggestions: [],
};

describe("ImprovementSuggestions", () => {
  it("shows a loading state while the suggestions are being fetched", async () => {
    server.use(http.get(SUGGESTIONS_URL, async () => (await delay("infinite"), HttpResponse.json(BASE_RESPONSE))));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-loading")).toBeInTheDocument();
  });

  it("renders a suggestion row with its icon-bearing title and impact badge", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "PLAYER_TIME",
          titleSv: "Om Erik Eriksson kunde träna Torsdag 18.00-19.30 skulle hen få plats i Grupp A.",
          detailSv: undefined,
          impactSv: "1 spelare färre på kölistan",
          groupId: "group-1",
          participantProfileId: "participant-1",
          coachProfileId: undefined,
          timeSlotId: "slot-1",
        },
        {
          kind: "COACH_TIME",
          titleSv: "Om Lisa Larsson kunde ta Torsdag 18.00-19.30 skulle Grupp A få en tränare.",
          detailSv: undefined,
          impactSv: "1 grupp utan tränare åtgärdas",
          groupId: "group-1",
          participantProfileId: undefined,
          coachProfileId: "coach-1",
          timeSlotId: "slot-1",
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    const rows = await screen.findAllByTestId("improvement-suggestion-row");
    expect(rows).toHaveLength(2);
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].impactSv)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[1].titleSv)).toBeInTheDocument();
    expect(screen.queryByTestId("improvement-suggestions-empty")).not.toBeInTheDocument();
  });

  it("renders the empty state when no suggestions were found", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(BASE_RESPONSE)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-empty")).toHaveTextContent(sv.results.suggestions.empty);
    expect(screen.queryByTestId("improvement-suggestion-row")).not.toBeInTheDocument();
    // Empty state has nothing to collapse - no toggle button rendered.
    expect(screen.queryByTestId("improvement-suggestions-toggle")).not.toBeInTheDocument();
  });

  it("shows the stale banner when the response is stale, alongside its content", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      stale: true,
      currentRevision: 4,
      suggestions: [
        {
          kind: "GROUP_MAX",
          titleSv: "Grupp A är full (max 2) – det hindrar Kalle Karlsson från en plats.",
          detailSv: undefined,
          impactSv: "hindrar 1 spelare från plats",
          groupId: "group-1",
          participantProfileId: "participant-2",
          coachProfileId: undefined,
          timeSlotId: undefined,
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-stale-banner")).toHaveTextContent(sv.results.suggestions.staleBanner);
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
  });

  it("shows the omittedCount note when suggestions were dropped by the backend cap", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      omittedCount: 2,
      suggestions: [
        {
          kind: "PLAYER_TIME_WISH",
          titleSv: "Om Kalle Karlsson kunde träna Torsdag 18.00-19.30 skulle hen hamna med Lisa Larsson i Grupp B.",
          detailSv: undefined,
          impactSv: "1 spelpar kan spela ihop",
          groupId: "group-2",
          participantProfileId: "participant-1",
          coachProfileId: undefined,
          timeSlotId: "slot-1",
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-omitted")).toHaveTextContent(sv.results.suggestions.omittedCount(2));
  });

  it("partitions actionable suggestions from GROUP_MAX limitations into separate sections", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "PLAYER_TIME",
          titleSv: "Om Erik Eriksson kunde träna Torsdag 18.00-19.30 skulle hen få plats i Grupp A.",
          detailSv: undefined,
          impactSv: "1 spelare färre på kölistan",
          groupId: "group-1",
          participantProfileId: "participant-1",
          coachProfileId: undefined,
          timeSlotId: "slot-1",
        },
        {
          kind: "GROUP_MAX",
          titleSv: "Grupp A är full (max 2) – det hindrar Kalle Karlsson från en plats.",
          detailSv: undefined,
          impactSv: "hindrar 1 spelare från plats",
          groupId: "group-1",
          participantProfileId: "participant-2",
          coachProfileId: undefined,
          timeSlotId: undefined,
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findAllByTestId("improvement-suggestion-row")).toHaveLength(1);
    expect(screen.getAllByTestId("improvement-limitation-row")).toHaveLength(1);
    expect(screen.getByText(sv.results.suggestions.limitationsHeading)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[1].titleSv)).toBeInTheDocument();
    expect(screen.queryByTestId("improvement-suggestions-empty")).not.toBeInTheDocument();
  });

  it("shows only the limitations section (no false empty text) when every suggestion is a limitation", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "GROUP_MAX_WISH",
          titleSv: "Grupp A är full (max 2) – det hindrar Kalle Karlsson och Lisa Larsson från att spela ihop.",
          detailSv: undefined,
          impactSv: "hindrar 1 spelpar från att spela ihop",
          groupId: "group-1",
          participantProfileId: undefined,
          coachProfileId: undefined,
          timeSlotId: undefined,
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findAllByTestId("improvement-limitation-row")).toHaveLength(1);
    expect(screen.queryByTestId("improvement-suggestion-row")).not.toBeInTheDocument();
    expect(screen.queryByTestId("improvement-suggestions-empty")).not.toBeInTheDocument();
    // The card subtitle promises "changes the council can make" - it must be hidden when the body
    // holds only fixed limitations, or the card contradicts its own content.
    expect(screen.queryByText(sv.results.suggestions.subtitle)).not.toBeInTheDocument();
  });

  it("renders a non-404 failure as an error message", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json({ error: "Run not found in plan plan-1: run-1" }, { status: 404 })));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-error")).toHaveTextContent("Run not found in plan plan-1: run-1");
  });

  it("renders a PRIORITY_ORDER suggestion in the actionable list, never as a limitation", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "PRIORITY_ORDER",
          titleSv: "Fler skulle kunna få sina önskemål uppfyllda med en annan prioritetsordning",
          detailSv:
            "2 av 2 granskade spelare med ouppfyllda önskemål skulle kunna flyttas som önskat utan att planen blir "
            + "sämre, om ordningen ändras till Tidigare grupp, Önskad träningstid, Träna tillsammans och Träningsnivå.",
          impactSv: "Vad optimeringen faktiskt väljer avgörs först när du kör om den.",
          groupId: undefined,
          participantProfileId: undefined,
          coachProfileId: undefined,
          timeSlotId: undefined,
          suggestedOrder: ["PREVIOUS_GROUP", "PREFERRED_TIME", "TRAIN_TOGETHER", "LEVEL"],
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findAllByTestId("improvement-suggestion-row")).toHaveLength(1);
    expect(screen.queryByTestId("improvement-limitation-row")).not.toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].detailSv!)).toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].impactSv)).toBeInTheDocument();
  });

  it("falls back to the actionable list (never a limitation, never a crash) for a kind unknown to this frontend build", async () => {
    // v0.4.1 design note: an unrecognized `kind` (e.g. a newer backend than this frontend build knows
    // about) must never be treated as a limitation and must never crash the icon lookup - it falls to
    // the actionable list with a fallback icon. Cast past the SuggestionKind union deliberately, since
    // this simulates a value this frontend build has never heard of.
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "SOME_FUTURE_KIND" as ImprovementSuggestionsResponse["suggestions"][number]["kind"],
          titleSv: "Ett framtida förslag denna frontend inte känner till.",
          detailSv: undefined,
          impactSv: "okänd effekt",
          groupId: undefined,
          participantProfileId: undefined,
          coachProfileId: undefined,
          timeSlotId: undefined,
          suggestedOrder: undefined,
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findAllByTestId("improvement-suggestion-row")).toHaveLength(1);
    expect(screen.queryByTestId("improvement-limitation-row")).not.toBeInTheDocument();
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
  });

  it("collapses and re-expands the suggestion list via the toggle button, defaulting open", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "COACH_MAX",
          titleSv: "Om Lisa Larsson kunde ta fler grupper (max nu 1) skulle Grupp A få en tränare.",
          detailSv: undefined,
          impactSv: "1 grupp utan tränare åtgärdas",
          groupId: "group-1",
          participantProfileId: undefined,
          coachProfileId: "coach-1",
          timeSlotId: "slot-1",
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    const user = userEvent.setup();
    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    // Defaults to open: the row is visible without any interaction.
    expect(await screen.findByText(response.suggestions[0].titleSv)).toBeVisible();

    await user.click(screen.getByTestId("improvement-suggestions-toggle"));
    await waitFor(() => expect(screen.getByText(response.suggestions[0].titleSv)).not.toBeVisible());

    await user.click(screen.getByTestId("improvement-suggestions-toggle"));
    await waitFor(() => expect(screen.getByText(response.suggestions[0].titleSv)).toBeVisible());
  });

  // v0.6.0 F5 (M-S5): SIMPLE mode never shows any other coach content on the Resultat tab (GroupCard's
  // chip/rows, ResultsSummary's coach-coverage badge, ScheduleView's coach names are all hidden) - a
  // COACH_TIME/COACH_MAX suggestion here would be the one leak left, see uiModeCoachHiding.test.tsx
  // for the full-component render assertion.
  it("hides COACH_TIME/COACH_MAX suggestion rows entirely in SIMPLE", async () => {
    const response: ImprovementSuggestionsResponse = {
      ...BASE_RESPONSE,
      suggestions: [
        {
          kind: "PLAYER_TIME",
          titleSv: "Om Erik Eriksson kunde träna Torsdag 18.00-19.30 skulle hen få plats i Grupp A.",
          detailSv: undefined,
          impactSv: "1 spelare färre på kölistan",
          groupId: "group-1",
          participantProfileId: "participant-1",
          coachProfileId: undefined,
          timeSlotId: "slot-1",
        },
        {
          kind: "COACH_TIME",
          titleSv: "Om Lisa Larsson kunde ta Torsdag 18.00-19.30 skulle Grupp A få en tränare.",
          detailSv: undefined,
          impactSv: "1 grupp utan tränare åtgärdas",
          groupId: "group-1",
          participantProfileId: undefined,
          coachProfileId: "coach-1",
          timeSlotId: "slot-1",
        },
      ],
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response)));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />, { uiMode: "SIMPLE" });

    const rows = await screen.findAllByTestId("improvement-suggestion-row");
    expect(rows).toHaveLength(1);
    expect(screen.getByText(response.suggestions[0].titleSv)).toBeInTheDocument();
    expect(screen.queryByText(response.suggestions[1].titleSv)).not.toBeInTheDocument();
  });
});

function suggestion(overrides: Partial<SuggestionView> = {}): SuggestionView {
  return {
    kind: "PLAYER_TIME",
    titleSv: "titel",
    detailSv: undefined,
    impactSv: "effekt",
    groupId: undefined,
    participantProfileId: undefined,
    coachProfileId: undefined,
    timeSlotId: undefined,
    ...overrides,
  };
}

describe("filterSuggestionsForUiMode", () => {
  it("drops COACH_TIME/COACH_MAX and keeps everything else when isSimple is true", () => {
    const all = [
      suggestion({ kind: "PLAYER_TIME" }),
      suggestion({ kind: "COACH_TIME" }),
      suggestion({ kind: "COACH_MAX" }),
      suggestion({ kind: "GROUP_MAX" }),
      suggestion({ kind: "GROUP_MAX_WISH" }),
      suggestion({ kind: "PLAYER_TIME_WISH" }),
    ];
    expect(filterSuggestionsForUiMode(all, true).map((s) => s.kind)).toEqual([
      "PLAYER_TIME",
      "GROUP_MAX",
      "GROUP_MAX_WISH",
      "PLAYER_TIME_WISH",
    ]);
  });

  it("keeps every kind unchanged when isSimple is false", () => {
    const all = [suggestion({ kind: "COACH_TIME" }), suggestion({ kind: "COACH_MAX" }), suggestion({ kind: "PLAYER_TIME" })];
    expect(filterSuggestionsForUiMode(all, false)).toEqual(all);
  });

  it("is a no-op on an empty list in either mode", () => {
    expect(filterSuggestionsForUiMode([], true)).toEqual([]);
    expect(filterSuggestionsForUiMode([], false)).toEqual([]);
  });
});
