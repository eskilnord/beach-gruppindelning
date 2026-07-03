import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { ImprovementSuggestions } from "./ImprovementSuggestions";
import type { ImprovementSuggestionsResponse } from "../../../api/types";

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
          titleSv: "Öka maxstorleken i Grupp A (nu 2) med 1 – då får Kalle Karlsson plats.",
          detailSv: undefined,
          impactSv: "1 spelare färre på kölistan",
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

  it("renders a non-404 failure as an error message", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json({ error: "Run not found in plan plan-1: run-1" }, { status: 404 })));

    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />);

    expect(await screen.findByTestId("improvement-suggestions-error")).toHaveTextContent("Run not found in plan plan-1: run-1");
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
});
