import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { ResultsSummary } from "./ResultsSummary";
import type { PlanExplanationResponse, RunResultSummary } from "../../../api/types";

const EXPLANATION_URL = "/api/plans/plan-1/runs/run-1/explanations/plan";

const BASE_EXPLANATION: PlanExplanationResponse = {
  runId: "run-1",
  basedOnRevision: 3,
  currentRevision: 3,
  stale: false,
  score: { hard: 0, medium: 0, soft: 0 },
  feasible: true,
  constraintSummaries: [],
  hardViolations: [],
  waitlist: [],
  problematicGroups: [],
  manualReview: [],
};

const RUN_SUMMARY: RunResultSummary = {
  hard: -5,
  medium: -600,
  soft: -1500,
  feasible: false,
  unassignedCount: 3,
  note: null,
  unchangedFromPrevious: false,
};

describe("ResultsSummary", () => {
  it("renders nothing when there is no run yet", () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId={undefined} runStartedAtLabel={undefined} runSummary={null} coachCoverage={null} />,
    );
    expect(screen.queryByTestId("results-quality-summary")).not.toBeInTheDocument();
  });

  it("renders nothing when a run exists but its summary failed to parse (e.g. a FAILED run)", () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="2026-07-03 10:00" runSummary={null} coachCoverage={null} />,
    );
    expect(screen.queryByTestId("results-quality-summary")).not.toBeInTheDocument();
  });

  it("renders the explain-based-on text and a soft-ONLY dimmed score line (review fix 2)", async () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary
        planId="plan-1"
        runId="run-1"
        runStartedAtLabel="2026-07-03 10:00"
        runSummary={RUN_SUMMARY}
        coachCoverage={null}
      />,
    );

    expect(screen.getByTestId("explain-based-on")).toHaveTextContent(sv.results.explainBasedOn("2026-07-03 10:00"));
    // Exactly the soft component (formatSoftLine's plain-ASCII thousands grouping) - NOT the full
    // formatScoreLine, whose weighted "|hard| hårda brott" magnitude could contradict the
    // hard-violations chip right above it and whose waitlist part duplicates the waitlist chip.
    expect(await screen.findByText("-1 500 mjukt")).toBeInTheDocument();
    expect(screen.queryByText((text) => text.includes("hårda brott ·"))).not.toBeInTheDocument();
  });

  it("falls back to the run summary's hard score magnitude while the plan explanation is still loading", async () => {
    server.use(http.get(EXPLANATION_URL, async () => (await delay("infinite"), HttpResponse.json(BASE_EXPLANATION))));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
    );

    // RUN_SUMMARY.hard is -5, so Math.abs(-5) = 5 hard violations, until the explanation (which would
    // report a different, more precise count) ever resolves.
    expect(await screen.findByText(sv.results.quality.hardViolations.bad(5))).toBeInTheDocument();
  });

  it("prefers the plan explanation's own hardViolations count once loaded, over the run summary's raw score", async () => {
    const response: PlanExplanationResponse = {
      ...BASE_EXPLANATION,
      hardViolations: [
        { key: "a", messageSv: "Grupp A är för stor" },
        { key: "b", messageSv: "Grupp B saknar tränare" },
      ],
    };
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(response)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
    );

    // 2 violation entries, not RUN_SUMMARY's raw magnitude of 5.
    expect(await screen.findByText(sv.results.quality.hardViolations.bad(2))).toBeInTheDocument();
  });

  it("renders a green hard-violations chip when there are none", async () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary
        planId="plan-1"
        runId="run-1"
        runStartedAtLabel="10:00"
        runSummary={{ ...RUN_SUMMARY, hard: 0 }}
        coachCoverage={null}
      />,
    );

    expect(await screen.findByText(sv.results.quality.hardViolations.ok)).toBeInTheDocument();
  });

  it("renders the waitlist chip's ok variant when nobody is unassigned", async () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary
        planId="plan-1"
        runId="run-1"
        runStartedAtLabel="10:00"
        runSummary={{ ...RUN_SUMMARY, unassignedCount: 0 }}
        coachCoverage={null}
      />,
    );

    expect(screen.getByText(sv.results.quality.waitlist.ok)).toBeInTheDocument();
  });

  it("renders the waitlist chip's bad variant with the unassigned count", () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
    );

    expect(screen.getByText(sv.results.quality.waitlist.bad(3))).toBeInTheDocument();
  });

  it("renders the coach-coverage chip when provided, and omits it when null", () => {
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(BASE_EXPLANATION)));
    const { rerender } = renderWithProviders(
      <ResultsSummary
        planId="plan-1"
        runId="run-1"
        runStartedAtLabel="10:00"
        runSummary={RUN_SUMMARY}
        coachCoverage={{ covered: 2, total: 3 }}
      />,
    );
    expect(screen.getByText(sv.results.quality.coachCoverage(2, 3))).toBeInTheDocument();

    rerender(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
    );
    expect(screen.queryByText(sv.results.quality.coachCoverage(2, 3))).not.toBeInTheDocument();
  });

  it("pluralizes Swedish correctly at count 1 (review fix 5): '1 hårt brott' and '1 av 1 grupp'", async () => {
    const response: PlanExplanationResponse = {
      ...BASE_EXPLANATION,
      hardViolations: [{ key: "a", messageSv: "Grupp A är för stor" }],
    };
    server.use(http.get(EXPLANATION_URL, () => HttpResponse.json(response)));
    renderWithProviders(
      <ResultsSummary
        planId="plan-1"
        runId="run-1"
        runStartedAtLabel="10:00"
        runSummary={RUN_SUMMARY}
        coachCoverage={{ covered: 1, total: 1 }}
      />,
    );

    // Literal strings on purpose - these pin the pluralized forms themselves, not just that the
    // component and sv.ts agree with each other.
    expect(await screen.findByText("1 hårt brott")).toBeInTheDocument();
    expect(screen.getByText("1 av 1 grupp har tränare")).toBeInTheDocument();
  });
});
