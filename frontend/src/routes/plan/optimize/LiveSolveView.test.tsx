import { describe, expect, it } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import type { LiveSnapshot } from "../../../api/types";
import { LiveSolveView } from "./LiveSolveView";

function snapshot(overrides: Partial<LiveSnapshot> = {}): LiveSnapshot {
  return {
    runId: "run-1",
    sequence: 1,
    hard: 0,
    medium: -300,
    soft: -20,
    feasible: true,
    improvementCount: 1,
    capturedAtMillis: 1_000,
    groups: [
      { groupId: "g1", name: "Grupp 1", players: [{ participantProfileId: "p1", displayName: "Alice", levelScaled: 50_000 }] },
      { groupId: "g2", name: "Grupp 2", players: [{ participantProfileId: "p2", displayName: "Bob", levelScaled: 60_000 }] },
    ],
    waitlist: [{ participantProfileId: "p3", displayName: "Carla", levelScaled: 40_000 }],
    ...overrides,
  };
}

describe("LiveSolveView", () => {
  it("renders every group with its players and the waitlist with its own players", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot()} running />);

    expect(screen.getByTestId("live-solve-view")).toBeInTheDocument();
    const groupBoxes = screen.getAllByTestId("live-group");
    expect(groupBoxes).toHaveLength(2);
    expect(within(groupBoxes[0]).getByText("Grupp 1")).toBeInTheDocument();
    expect(within(groupBoxes[0]).getByText("Alice")).toBeInTheDocument();
    expect(within(groupBoxes[1]).getByText("Bob")).toBeInTheDocument();

    const waitlist = screen.getByTestId("live-waitlist");
    expect(within(waitlist).getByText(sv.optimize.live.waitlistLabel(1))).toBeInTheDocument();
    expect(within(waitlist).getByText("Carla")).toBeInTheDocument();

    // Score line + improvement counter (spec: "0 hårda brott · 1 på kölista · -20 mjukt").
    expect(screen.getByTestId("live-solve-score-line")).toHaveTextContent("0 hårda brott");
    expect(screen.getByTestId("live-solve-score-line")).toHaveTextContent("1 på kölista");
    expect(screen.getByText(sv.optimize.live.improvementNumber(1))).toBeInTheDocument();
  });

  it("shows a group's empty-state label when it has no players", () => {
    renderWithProviders(
      <LiveSolveView
        planId="plan-1"
        snapshot={snapshot({ groups: [{ groupId: "g1", name: "Grupp 1", players: [] }], waitlist: [] })}
        running
      />,
    );

    expect(within(screen.getByTestId("live-group")).getByText(sv.optimize.live.emptyGroup)).toBeInTheDocument();
    expect(within(screen.getByTestId("live-waitlist")).getByText(sv.optimize.live.emptyWaitlist)).toBeInTheDocument();
  });

  it("flashes a player who moved to a different group between two rendered frames, but not one who stayed put", () => {
    const { rerender } = renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot({ sequence: 1 })} running />);

    // Alice (p1) moves from Grupp 1 to Grupp 2; Bob (p2) stays in Grupp 2.
    const moved = snapshot({
      sequence: 2,
      groups: [
        { groupId: "g1", name: "Grupp 1", players: [] },
        {
          groupId: "g2",
          name: "Grupp 2",
          players: [
            { participantProfileId: "p2", displayName: "Bob", levelScaled: 60_000 },
            { participantProfileId: "p1", displayName: "Alice", levelScaled: 50_000 },
          ],
        },
      ],
    });
    rerender(<LiveSolveView planId="plan-1" snapshot={moved} running />);

    expect(screen.getByText("Alice").closest(".gp-live-flash")).not.toBeNull();
    expect(screen.getByText("Bob").closest(".gp-live-flash")).toBeNull();
  });

  it("flashes a player who left the waitlist and got placed into a group", () => {
    const { rerender } = renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot({ sequence: 1 })} running />);

    const placed = snapshot({
      sequence: 2,
      groups: [
        {
          groupId: "g1",
          name: "Grupp 1",
          players: [
            { participantProfileId: "p1", displayName: "Alice", levelScaled: 50_000 },
            { participantProfileId: "p3", displayName: "Carla", levelScaled: 40_000 },
          ],
        },
        { groupId: "g2", name: "Grupp 2", players: [{ participantProfileId: "p2", displayName: "Bob", levelScaled: 60_000 }] },
      ],
      waitlist: [],
    });
    rerender(<LiveSolveView planId="plan-1" snapshot={placed} running />);

    expect(screen.getByText("Carla").closest(".gp-live-flash")).not.toBeNull();
  });

  it("does not flash anything on the very first frame rendered (nothing to compare against yet)", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot({ sequence: 0 })} running />);

    expect(screen.getByText("Alice").closest(".gp-live-flash")).toBeNull();
  });

  it("shows the dimmed finished hint with a link to Resultat once the solve has settled", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot()} running={false} />);

    expect(screen.getByText(sv.optimize.live.finishedHint)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.optimize.live.goToResultsLink })).toBeInTheDocument();
  });

  it("does not show the finished hint while still running", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={snapshot()} running />);

    expect(screen.queryByText(sv.optimize.live.finishedHint)).not.toBeInTheDocument();
  });
});
