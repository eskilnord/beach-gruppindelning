import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { SuggestDurationCard } from "./SuggestDurationCard";

const SUGGESTION = {
  suggestedSeconds: 180,
  machineSpeedFactor: 1.2,
  benchmarkMs: 2000,
  problemSize: { participants: 130, groups: 12, activeBlocks: 11, coaches: 3, wishes: 45, customFieldConstraints: 2 },
  rationaleSv:
    "Baserat på 130 spelare, 12 grupper, 45 önskemål och din dators hastighet (1.2× referens) föreslås 180 sekunder.",
};

describe("SuggestDurationCard", () => {
  it("shows the loading state while the suggestion is being computed", async () => {
    server.use(
      http.post("/api/plans/plan-1/solve/suggest-duration", async () => {
        await delay("infinite");
        return HttpResponse.json(SUGGESTION);
      }),
    );

    renderWithProviders(<SuggestDurationCard planId="plan-1" solveActive={false} startPending={false} onOptimize={() => {}} />);

    expect(await screen.findByTestId("suggest-loading")).toBeInTheDocument();
    expect(screen.getByText(sv.optimize.suggest.loading)).toBeInTheDocument();
  });

  it("renders the suggestion (seconds + rationale + problem size) and submits it via the primary button", async () => {
    server.use(http.post("/api/plans/plan-1/solve/suggest-duration", () => HttpResponse.json(SUGGESTION)));

    const onOptimize = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<SuggestDurationCard planId="plan-1" solveActive={false} startPending={false} onOptimize={onOptimize} />);

    expect(await screen.findByText(sv.optimize.suggest.suggestedSeconds(180))).toBeInTheDocument();
    expect(screen.getByText(SUGGESTION.rationaleSv)).toBeInTheDocument();
    expect(screen.getByTestId("suggest-problem-size")).toHaveTextContent(
      sv.optimize.suggest.problemSummary(SUGGESTION.problemSize),
    );

    await user.click(screen.getByRole("button", { name: sv.optimize.suggest.optimizeButton(180) }));
    expect(onOptimize).toHaveBeenCalledWith(180);
  });

  it("renders a 409 (solve active on the backend) as the calm solve-active info state, not an error", async () => {
    server.use(
      http.post("/api/plans/plan-1/solve/suggest-duration", () =>
        HttpResponse.json({ error: "A solve is currently running for plan plan-1" }, { status: 409 }),
      ),
    );

    renderWithProviders(<SuggestDurationCard planId="plan-1" solveActive={false} startPending={false} onOptimize={() => {}} />);

    expect(await screen.findByTestId("suggest-solve-active")).toHaveTextContent(sv.optimize.suggest.solveActive);
    expect(screen.queryByTestId("suggest-error")).not.toBeInTheDocument();
  });

  it("renders a non-409 failure as an error with a retry button", async () => {
    server.use(
      http.post("/api/plans/plan-1/solve/suggest-duration", () =>
        HttpResponse.json({ error: "Activity plan not found: plan-1" }, { status: 404 }),
      ),
    );

    renderWithProviders(<SuggestDurationCard planId="plan-1" solveActive={false} startPending={false} onOptimize={() => {}} />);

    expect(await screen.findByTestId("suggest-error")).toHaveTextContent("Activity plan not found: plan-1");
    expect(screen.getByRole("button", { name: sv.optimize.suggest.retryButton })).toBeInTheDocument();
  });

  it("pauses entirely (solve-active info, no request) while a solve is running locally", () => {
    server.use(
      http.post("/api/plans/plan-1/solve/suggest-duration", () => {
        throw new Error("must not be called while solveActive");
      }),
    );

    renderWithProviders(<SuggestDurationCard planId="plan-1" solveActive startPending={false} onOptimize={() => {}} />);

    expect(screen.getByTestId("suggest-solve-active")).toBeInTheDocument();
    expect(screen.queryByTestId("suggest-loading")).not.toBeInTheDocument();
  });
});
