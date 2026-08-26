import { act } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
import { useUiModeStore } from "../../../lib/uiMode/uiModeStore";
import { PrioritiesPanel } from "./PrioritiesPanel";
import { priorityOrderKey, type PriorityOrderView } from "../../../api/priorityOrder";

const PLAN_ID = "plan-1";
const OTHER_PLAN_ID = "plan-2";

const NORMAL_ORDER: PriorityOrderView = {
  order: ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"],
  defaultOrder: ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"],
  matchesOrder: true,
  customWeightsActive: false,
  otherOverridesActive: false,
  staleSinceLastRun: false,
  updatedAt: null,
  priorities: [
    {
      key: "TRAIN_TOGETHER",
      rank: 1,
      labelSv: "Träna tillsammans",
      summarySv: "Spelare som vill spela ihop hamnar i samma grupp.",
      constraintKeys: ["wantSameGroup"],
      weights: { wantSameGroup: 400 },
      enabled: true,
    },
    {
      key: "PREVIOUS_GROUP",
      rank: 2,
      labelSv: "Fortsätta i samma grupp",
      summarySv: "Spelare hamnar helst i samma grupp som förra terminen.",
      constraintKeys: ["previousGroupContinuity"],
      weights: { previousGroupContinuity: 300 },
      enabled: true,
    },
    {
      key: "PREFERRED_TIME",
      rank: 3,
      labelSv: "Önskad tid",
      summarySv: "Spelare hamnar helst på den tid de önskat.",
      constraintKeys: ["preferredTime"],
      weights: { preferredTime: 200 },
      enabled: true,
    },
    {
      key: "LEVEL",
      rank: 4,
      labelSv: "Jämn nivå",
      // Deliberately worded differently from sv.simple.priorities.explanations.LEVEL (both render on
      // screen at once - the row's own explanation plus this accordion summary - so an identical
      // fixture string would make the "renders verbatim" assertion below ambiguous about which one
      // it actually found).
      summarySv: "Nivåspridningen mellan grupperna hålls så liten som möjligt.",
      constraintKeys: ["levelBalance"],
      weights: { levelBalance: 100 },
      enabled: true,
    },
  ],
};

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const result = render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/plans/${PLAN_ID}/prioriteringar`]}>
          <Routes>
            <Route path="/plans/:planId/prioriteringar" element={<PrioritiesPanel />} />
            <Route path="/plans/:planId/optimering" element={<div data-testid="optimering-route" />} />
            <Route path="/plans/:planId/falt" element={<div data-testid="falt-route" />} />
          </Routes>
          <LocationDisplay />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { ...result, queryClient };
}

/** v0.6.0 F3 review fix (FIX 3, BLOCKER): a variant that also renders a plain nav button switching
 *  the route from `/plans/plan-1/prioriteringar` to `/plans/plan-2/prioriteringar` - the SAME mounted
 *  `<PrioritiesPanel>` instance then just receives a new `planId` route param, exactly the scenario
 *  the "reset ALL local state when planId changes" fix targets. */
function renderPanelWithPlanSwitcher() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const result = render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/plans/${PLAN_ID}/prioriteringar`]}>
          <Routes>
            <Route path="/plans/:planId/prioriteringar" element={<PrioritiesPanel />} />
          </Routes>
          <PlanSwitcher />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { ...result, queryClient };
}

function PlanSwitcher() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(`/plans/${OTHER_PLAN_ID}/prioriteringar`)}>
      go-to-other-plan
    </button>
  );
}

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="current-path">{location.pathname}</div>;
}

function mockGet(response: PriorityOrderView, planId: string = PLAN_ID) {
  server.use(http.get(`/api/plans/${planId}/priority-order`, () => HttpResponse.json(response)));
}

/** A PUT handler that never resolves until the returned function is called - used to hold a save
 *  "in flight" (dirty/pending) so a test can assert something about that window (FIX 2/FIX 3). */
function mockHangingPut(planId: string = PLAN_ID) {
  let release: (() => void) | null = null;
  server.use(
    http.put(`/api/plans/${planId}/priority-order`, async ({ request }) => {
      const body = (await request.json()) as { order: string[] };
      await new Promise<void>((resolve) => {
        release = resolve;
      });
      return HttpResponse.json({ ...NORMAL_ORDER, order: body.order, matchesOrder: true, customWeightsActive: false });
    }),
  );
  return () => release?.();
}

function rowKeys() {
  return screen.getAllByTestId("priority-row").map((row) => row.getAttribute("data-priority-key"));
}

afterEach(() => {
  vi.useRealTimers();
});

describe("PrioritiesPanel", () => {
  it("renders the heading, intro and rows in the loaded order with backend labels + frontend explanations", async () => {
    mockGet(NORMAL_ORDER);
    renderPanel();

    expect(screen.getByText(sv.simple.priorities.heading)).toBeInTheDocument();
    expect(screen.getByText(sv.simple.priorities.intro)).toBeInTheDocument();

    const rows = await screen.findAllByTestId("priority-row");
    expect(rows).toHaveLength(4);
    expect(rows.map((row) => row.getAttribute("data-priority-key"))).toEqual([
      "TRAIN_TOGETHER",
      "PREVIOUS_GROUP",
      "PREFERRED_TIME",
      "LEVEL",
    ]);
    expect(within(rows[0]).getByText("Träna tillsammans")).toBeInTheDocument();
    expect(within(rows[0]).getByText(sv.simple.priorities.explanations.TRAIN_TOGETHER)).toBeInTheDocument();
  });

  // v0.6.0 F3 review fix (a11y, FIX 10 MINOR).
  it("exposes the save-status region as role=status/aria-live=polite", async () => {
    mockGet(NORMAL_ORDER);
    renderPanel();
    await screen.findAllByTestId("priority-row");

    const status = screen.getByTestId("priority-save-status");
    expect(status).toHaveAttribute("role", "status");
    expect(status).toHaveAttribute("aria-live", "polite");
  });

  it("renders the interpretation accordion with each priority's summarySv verbatim", async () => {
    mockGet(NORMAL_ORDER);
    renderPanel();

    await screen.findAllByTestId("priority-row");
    const user = userEvent.setup();
    await user.click(screen.getByText(sv.simple.priorities.interpretationHeading));

    for (const row of NORMAL_ORDER.priorities) {
      expect(await screen.findByText(row.summarySv)).toBeInTheDocument();
    }
  });

  // v0.6.0 F3 review fix (FIX 10, MINOR): the accordion previously always followed `priorities`'
  // server (rank) order - here it must follow the currently DISPLAYED order instead.
  it("sorts the interpretation accordion by the displayed order, not the server's raw priorities array order", async () => {
    mockGet(NORMAL_ORDER);
    renderPanel();
    const rows = await screen.findAllByTestId("priority-row");

    const user = userEvent.setup();
    // Move the last row (LEVEL) to the top via its up-arrow, 3 times - no debounce advance needed,
    // the accordion should already reflect the OPTIMISTIC local order immediately.
    const lastRow = rows[3];
    await user.click(within(lastRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Jämn nivå") }));
    await user.click(within(lastRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Jämn nivå") }));
    await user.click(within(lastRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Jämn nivå") }));

    expect(rowKeys()).toEqual(["LEVEL", "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME"]);

    await user.click(screen.getByText(sv.simple.priorities.interpretationHeading));
    const summaryRows = screen.getAllByTestId("priority-summary-row");
    expect(within(summaryRows[0]).getByText("Jämn nivå")).toBeInTheDocument();
    expect(within(summaryRows[1]).getByText("Träna tillsammans")).toBeInTheDocument();
  });

  it("autosaves a reorder 600ms after the last edit, showing Sparar… then Sparat ✓", async () => {
    let putBody: unknown;
    mockGet(NORMAL_ORDER);
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, async ({ request }) => {
        putBody = await request.json();
        const order = (putBody as { order: string[] }).order;
        return HttpResponse.json({ ...NORMAL_ORDER, order, matchesOrder: true, customWeightsActive: false });
      }),
    );
    renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();
    const secondRow = screen.getAllByTestId("priority-row")[1];
    fireEvent.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") }));

    // Optimistic order update + "Sparar…" shown immediately, before the debounce has fired.
    expect(screen.getByTestId("priority-save-status")).toHaveTextContent(sv.simple.priorities.saving);
    expect(screen.getAllByTestId("priority-row")[0]).toHaveAttribute("data-priority-key", "PREVIOUS_GROUP");

    act(() => {
      vi.advanceTimersByTime(600);
    });
    vi.useRealTimers();

    expect(await screen.findByText(sv.simple.priorities.saved)).toBeInTheDocument();
    expect(putBody).toEqual({ order: ["PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"] });
  });

  // v0.6.0 F3 review fix (FIX 10, MINOR): three rapid edits within the 600ms debounce window must
  // coalesce to exactly ONE PUT (the debounce's whole point) - pinned explicitly rather than only
  // being an incidental side effect of other tests.
  it("coalesces three rapid clicks within the debounce window into a single PUT", async () => {
    let putCount = 0;
    let lastBody: unknown;
    mockGet(NORMAL_ORDER);
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, async ({ request }) => {
        putCount += 1;
        lastBody = await request.json();
        const order = (lastBody as { order: string[] }).order;
        return HttpResponse.json({ ...NORMAL_ORDER, order, matchesOrder: true, customWeightsActive: false });
      }),
    );
    renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();
    // LEVEL's row - captured once via its stable aria-label, so the same button reference is reused
    // for all three clicks even though the row itself moves up in the list after each one (React
    // keeps the same DOM node under the `key={key}` reconciliation - see PriorityRankList.tsx).
    const lastRow = screen.getAllByTestId("priority-row")[3];
    const upButton = within(lastRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Jämn nivå") });

    // Three clicks, each well within the 600ms debounce window of the previous one.
    fireEvent.click(upButton);
    act(() => vi.advanceTimersByTime(100));
    fireEvent.click(upButton);
    act(() => vi.advanceTimersByTime(100));
    fireEvent.click(upButton);

    expect(putCount).toBe(0); // nothing sent yet - still inside the debounce window.

    act(() => {
      vi.advanceTimersByTime(600);
    });
    vi.useRealTimers();

    await screen.findByText(sv.simple.priorities.saved);
    expect(putCount).toBe(1);
    // Only the FINAL committed order is ever sent - not one PUT per click.
    expect(lastBody).toEqual({ order: rowKeys() });
  });

  it("reverts the optimistic order and shows the error + retry on a failed PUT", async () => {
    mockGet(NORMAL_ORDER);
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, () => HttpResponse.json({ error: "Kunde inte spara" }, { status: 500 })),
    );
    renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();
    const secondRow = screen.getAllByTestId("priority-row")[1];
    fireEvent.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") }));
    act(() => {
      vi.advanceTimersByTime(600);
    });
    vi.useRealTimers();

    expect(await screen.findByText("Kunde inte spara")).toBeInTheDocument();
    // Reverted back to the original, last-confirmed order.
    expect(screen.getAllByTestId("priority-row")[0]).toHaveAttribute("data-priority-key", "TRAIN_TOGETHER");

    // Retry re-attempts the exact same (failed) order, this time succeeding.
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, () =>
        HttpResponse.json({
          ...NORMAL_ORDER,
          order: ["PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"],
        }),
      ),
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: sv.simple.priorities.retryButton }));

    expect(await screen.findByText(sv.simple.priorities.saved)).toBeInTheDocument();
    expect(screen.getAllByTestId("priority-row")[0]).toHaveAttribute("data-priority-key", "PREVIOUS_GROUP");
  });

  // v0.6.0 F3 review fix (FIX 10, MINOR): a 409 (e.g. the plan is mid-solve, backend rejects the
  // write) must surface exactly like any other ApiError - the backend's Swedish message shown, and
  // the optimistic edit reverted - not a special/blank state.
  it("surfaces a Swedish message and reverts on a 409 (plan mid-solve)", async () => {
    mockGet(NORMAL_ORDER);
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, () =>
        HttpResponse.json({ error: "Planen optimeras just nu och kan inte ändras." }, { status: 409 }),
      ),
    );
    renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();
    const secondRow = screen.getAllByTestId("priority-row")[1];
    fireEvent.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") }));
    act(() => {
      vi.advanceTimersByTime(600);
    });
    vi.useRealTimers();

    expect(await screen.findByText("Planen optimeras just nu och kan inte ändras.")).toBeInTheDocument();
    expect(screen.getAllByTestId("priority-row")[0]).toHaveAttribute("data-priority-key", "TRAIN_TOGETHER");
  });

  // v0.6.0 F3 review fix (FIX 1, BLOCKER): the debounced autosave must not simply be cancelled if
  // the admin navigates away (unmounts) within the 600ms window - the pending edit's PUT must still
  // fire (`flushOnUnmount: true`).
  it("flushes a pending debounced save on unmount instead of dropping it", async () => {
    let putBody: unknown;
    mockGet(NORMAL_ORDER);
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, async ({ request }) => {
        putBody = await request.json();
        const order = (putBody as { order: string[] }).order;
        return HttpResponse.json({ ...NORMAL_ORDER, order, matchesOrder: true, customWeightsActive: false });
      }),
    );
    const { unmount } = renderPanel();
    await screen.findAllByTestId("priority-row");

    const secondRow = screen.getAllByTestId("priority-row")[1];
    fireEvent.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") }));

    // Unmount well within the 600ms debounce window - nothing has been sent yet.
    expect(putBody).toBeUndefined();
    act(() => {
      unmount();
    });

    await waitFor(() => expect(putBody).toEqual({ order: ["PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"] }));
  });

  // v0.6.0 F3 review fix (FIX 2, BLOCKER, PUT sequencing): mirrors src/lib/uiMode/useUiMode.ts's B4
  // fix. Two save attempts can be in flight at once and resolve out of order; the older response
  // must be a no-op once a newer attempt has since started.
  it("drops a stale (out-of-order) PUT response instead of clobbering a newer confirmed save", async () => {
    mockGet(NORMAL_ORDER);
    let putCount = 0;
    let releaseFirst: (() => void) | null = null;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, async ({ request }) => {
        const body = (await request.json()) as { order: string[] };
        putCount += 1;
        if (putCount === 1) {
          // Attempt #1 hangs until manually released - it will resolve AFTER attempt #2 below.
          await new Promise<void>((resolve) => {
            releaseFirst = resolve;
          });
        }
        return HttpResponse.json({ ...NORMAL_ORDER, order: body.order, matchesOrder: true, customWeightsActive: false });
      }),
    );
    renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();

    // Attempt #1: move PREVIOUS_GROUP up (fires and hangs).
    fireEvent.click(
      within(screen.getAllByTestId("priority-row")[1]).getByRole("button", {
        name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp"),
      }),
    );
    act(() => vi.advanceTimersByTime(600));

    // Attempt #2: a second, distinct edit fires strictly after attempt #1 and resolves immediately -
    // it becomes the latest attempt.
    fireEvent.click(
      within(screen.getAllByTestId("priority-row")[0]).getByRole("button", {
        name: sv.simple.priorities.moveDownAriaLabel("Fortsätta i samma grupp"),
      }),
    );
    act(() => vi.advanceTimersByTime(600));

    vi.useRealTimers();
    await screen.findByText(sv.simple.priorities.saved);
    const orderAfterAttempt2 = rowKeys();

    // Now let the STALE attempt #1 resolve - it must be silently dropped.
    await act(async () => {
      releaseFirst?.();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(rowKeys()).toEqual(orderAfterAttempt2);
    expect(screen.getByText(sv.simple.priorities.saved)).toBeInTheDocument();
  });

  // v0.6.0 F3 review fix (FIX 3, BLOCKER, local-state sync policy).
  it("re-syncs the displayed order from a background refetch when there is no pending local edit", async () => {
    mockGet(NORMAL_ORDER);
    const { queryClient } = renderPanel();
    await screen.findAllByTestId("priority-row");

    const REFETCHED: PriorityOrderView = { ...NORMAL_ORDER, order: ["LEVEL", "PREFERRED_TIME", "PREVIOUS_GROUP", "TRAIN_TOGETHER"] };
    act(() => {
      queryClient.setQueryData(priorityOrderKey(PLAN_ID), REFETCHED);
    });

    await waitFor(() => expect(rowKeys()).toEqual(REFETCHED.order));
  });

  it("does NOT let a background refetch clobber a pending (unsaved) local edit", async () => {
    mockGet(NORMAL_ORDER);
    const releasePut = mockHangingPut();
    const { queryClient } = renderPanel();
    await screen.findAllByTestId("priority-row");

    vi.useFakeTimers();
    fireEvent.click(
      within(screen.getAllByTestId("priority-row")[1]).getByRole("button", {
        name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp"),
      }),
    );
    act(() => vi.advanceTimersByTime(600)); // fires the PUT, which now hangs (dirty stays true).
    vi.useRealTimers();

    const editedOrder = rowKeys();
    expect(editedOrder).toEqual(["PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"]);

    const REFETCHED: PriorityOrderView = { ...NORMAL_ORDER, order: ["LEVEL", "PREFERRED_TIME", "PREVIOUS_GROUP", "TRAIN_TOGETHER"] };
    act(() => {
      queryClient.setQueryData(priorityOrderKey(PLAN_ID), REFETCHED);
    });

    // The pending local edit is still shown, untouched by the background refetch.
    expect(rowKeys()).toEqual(editedOrder);

    releasePut();
  });

  it("resets all local state (order, save status, dirty flag) when planId changes", async () => {
    const OTHER_ORDER: PriorityOrderView = {
      ...NORMAL_ORDER,
      order: ["LEVEL", "PREFERRED_TIME", "PREVIOUS_GROUP", "TRAIN_TOGETHER"],
    };
    mockGet(NORMAL_ORDER, PLAN_ID);
    mockGet(OTHER_ORDER, OTHER_PLAN_ID);
    let plan1PutCount = 0;
    let plan2PutBody: unknown;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/priority-order`, () => {
        plan1PutCount += 1;
        return HttpResponse.json(NORMAL_ORDER);
      }),
      http.put(`/api/plans/${OTHER_PLAN_ID}/priority-order`, async ({ request }) => {
        plan2PutBody = await request.json();
        return HttpResponse.json(OTHER_ORDER);
      }),
    );
    renderPanelWithPlanSwitcher();
    await screen.findAllByTestId("priority-row");

    // Start (and leave PENDING, still inside the 600ms debounce window) a local edit on plan-1.
    vi.useFakeTimers();
    fireEvent.click(
      within(screen.getAllByTestId("priority-row")[1]).getByRole("button", {
        name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp"),
      }),
    );
    expect(screen.getByTestId("priority-save-status")).toHaveTextContent(sv.simple.priorities.saving);

    // Switch to a different plan (same mounted route/component, new :planId param) BEFORE the
    // debounce would have fired.
    fireEvent.click(screen.getByText("go-to-other-plan"));

    // The save status resets immediately (idle) rather than carrying plan-1's "Sparar…" over.
    expect(screen.getByTestId("priority-save-status")).toHaveTextContent("");

    // Advance well past 600ms: if the pending debounce from plan-1 had NOT been cancelled on the
    // planId switch, it would now fire and PUT plan-1's edited order at plan-2's endpoint (both
    // `debouncedSave` and `setPriorityOrder` close over the latest render's `planId`/mutate by the
    // time a real timer fires) - asserting neither PUT saw it pins that this can't happen.
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    vi.useRealTimers();

    expect(plan1PutCount).toBe(0);
    expect(plan2PutBody).toBeUndefined();

    // And the list re-loads as plan-2's own (unrelated) order, not plan-1's edited one.
    await waitFor(() => expect(rowKeys()).toEqual(OTHER_ORDER.order));
  });

  it("shows the stale callout with a working 'Gå till Optimera' link when staleSinceLastRun", async () => {
    mockGet({ ...NORMAL_ORDER, staleSinceLastRun: true });
    renderPanel();
    await screen.findAllByTestId("priority-row");

    expect(screen.getByTestId("priority-stale-alert")).toHaveTextContent(sv.simple.priorities.staleAlert.message);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: sv.simple.priorities.staleAlert.button }));

    expect(await screen.findByTestId("optimering-route")).toBeInTheDocument();
  });

  it("shows the dimmed 'other overrides' note when otherOverridesActive without customWeightsActive", async () => {
    mockGet({ ...NORMAL_ORDER, otherOverridesActive: true });
    renderPanel();
    await screen.findAllByTestId("priority-row");

    expect(screen.getByText(sv.simple.priorities.otherOverridesNote)).toBeInTheDocument();
    expect(screen.queryByTestId("priority-overrides-alert")).not.toBeInTheDocument();
  });

  describe("customWeightsActive", () => {
    const CUSTOM: PriorityOrderView = { ...NORMAL_ORDER, matchesOrder: false, customWeightsActive: true };

    it("dims + disables the rank list and shows the overrides alert, with the inference-honesty sentence", async () => {
      mockGet(CUSTOM);
      renderPanel();
      await screen.findAllByTestId("priority-row");

      const alert = screen.getByTestId("priority-overrides-alert");
      expect(alert).toHaveTextContent(sv.simple.priorities.overridesAlert.title);
      expect(alert).toHaveTextContent(sv.simple.priorities.overridesAlert.body);
      // v0.6.0 F3 review fix (FIX 6, MAJOR): the honesty sentence is part of `body` above, but pinned
      // again explicitly here so a future edit to `body` can't silently drop it unnoticed.
      expect(alert).toHaveTextContent("Ordningen nedan är vår tolkning av de anpassade vikterna.");
      expect(screen.getByTestId("priority-rank-list")).toHaveAttribute("aria-disabled", "true");
      screen.getAllByRole("button", { name: /Flytta/ }).forEach((button) => expect(button).toBeDisabled());
    });

    it("'Öppna avancerat läge' switches to ADVANCED and navigates to falt", async () => {
      mockGet(CUSTOM);
      server.use(http.put("/api/app-settings", async () => HttpResponse.json({ uiMode: "ADVANCED" })));
      renderPanel();
      await screen.findAllByTestId("priority-row");

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: sv.simple.priorities.overridesAlert.openAdvancedButton }));

      expect(await screen.findByTestId("falt-route")).toBeInTheDocument();
      expect(useUiModeStore.getState().mode).toBe("ADVANCED");
    });

    it("'Återställ till prioriteringsordning' confirms, PUTs the shown order, and clears the alert", async () => {
      let putBody: unknown;
      mockGet(CUSTOM);
      server.use(
        http.put(`/api/plans/${PLAN_ID}/priority-order`, async ({ request }) => {
          putBody = await request.json();
          return HttpResponse.json({ ...NORMAL_ORDER, matchesOrder: true, customWeightsActive: false });
        }),
        http.get(`/api/plans/${PLAN_ID}/constraint-weights`, () => HttpResponse.json([])),
      );
      renderPanel();
      await screen.findAllByTestId("priority-row");

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: sv.simple.priorities.overridesAlert.resetButton }));

      const dialog = await screen.findByRole("dialog");
      // v0.6.0 F3 review fix (FIX 6, MAJOR): the confirm message now also states plainly that the
      // shown order becomes the saved one.
      expect(dialog).toHaveTextContent(sv.simple.priorities.resetConfirm.message);
      expect(dialog).toHaveTextContent("Ordningen som visas blir då den som gäller, och de anpassade vikterna ersätts.");
      await user.click(within(dialog).getByRole("button", { name: sv.simple.priorities.resetConfirm.confirmLabel }));

      expect(putBody).toEqual({ order: CUSTOM.order });
      expect(screen.queryByTestId("priority-overrides-alert")).not.toBeInTheDocument();
      await screen.findByText(sv.simple.priorities.heading); // still on the panel, no crash
    });

    // v0.6.0 F3 review fix (FIX 10, MINOR): a failed reset's error is rendered as its OWN red Alert
    // above the explanatory message - not swapped in place of it (DeleteConfirmModal.tsx's
    // `errorMessage` prop).
    it("a failed reset shows the error as a red Alert ABOVE the (still-visible) explanatory message", async () => {
      mockGet(CUSTOM);
      server.use(
        http.put(`/api/plans/${PLAN_ID}/priority-order`, () =>
          HttpResponse.json({ error: "Kunde inte återställa" }, { status: 500 }),
        ),
      );
      renderPanel();
      await screen.findAllByTestId("priority-row");

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: sv.simple.priorities.overridesAlert.resetButton }));
      const dialog = await screen.findByRole("dialog");
      await user.click(within(dialog).getByRole("button", { name: sv.simple.priorities.resetConfirm.confirmLabel }));

      expect(await within(dialog).findByText("Kunde inte återställa")).toBeInTheDocument();
      // The original explanatory message is STILL shown too, not replaced by the error.
      expect(within(dialog).getByText(sv.simple.priorities.resetConfirm.message)).toBeInTheDocument();
    });
  });
});
