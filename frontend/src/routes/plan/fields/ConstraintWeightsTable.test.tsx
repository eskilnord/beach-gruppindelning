import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { ConstraintWeightsTable } from "./ConstraintWeightsTable";
import type { ConstraintDefinition, ConstraintWeightView } from "../../../api/types";

const DEFINITION: ConstraintDefinition = {
  key: "levelBalance",
  label: "Nivåbalans",
  description: "Håller nivåspridningen inom en grupp jämn.",
  constraintCategory: "Nivå",
  defaultWeight: 100,
  hardOrSoft: "SOFT",
  enabled: true,
};

const WEIGHT_VIEW: ConstraintWeightView = {
  key: "levelBalance",
  label: "Nivåbalans",
  description: "Håller nivåspridningen inom en grupp jämn.",
  constraintCategory: "Nivå",
  hardOrSoft: "SOFT",
  weight: 100,
  enabled: true,
  overridden: false,
};

/**
 * v0.3.0 WI-3 smoke test: the Konfiguration sub-tab's standard-constraints table gained a
 * section-level HelpTip plus one per Hård/Mjuk, Vikt and Aktiverad column header. Only asserts the
 * HelpTip trigger buttons render (count, not copy) - see HelpTip.test.tsx for the component's own
 * open/close/aria behavior.
 */
describe("ConstraintWeightsTable help tips", () => {
  it("renders a section-level HelpTip and one per explained column header", async () => {
    server.use(
      http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([WEIGHT_VIEW])),
      http.get("/api/constraint-definitions", () => HttpResponse.json([DEFINITION])),
    );

    renderWithProviders(<ConstraintWeightsTable planId="plan-1" />);

    await screen.findByText("Nivåbalans");

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // section heading + Hård/Mjuk + Vikt + Aktiverad column headers = 4
    expect(helpTips.length).toBeGreaterThanOrEqual(4);
  });
});
