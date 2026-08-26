import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach } from "vitest";
import { server } from "./server";
import { setUiModeForTests } from "../lib/uiMode/uiModeStore";
import { UI_MODE_STORAGE_KEY } from "../lib/uiMode/uiMode";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
// Belt-and-suspenders alongside the afterEach reset below: a spec that renders WITHOUT
// renderWithProviders (which itself calls setUiModeForTests per render - e.g. OptimizePanel/
// ResourcesPanel/ExportPanel specs that build their own MantineProvider/QueryClientProvider/
// MemoryRouter wrapper directly) would otherwise have its very FIRST test run against whatever
// resolveInitialUiMode() resolved to at module-load time in jsdom, not the documented ADVANCED test
// default - this beforeEach makes that first test's starting mode deterministic too.
beforeEach(() => {
  setUiModeForTests("ADVANCED");
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
  // Reset the global UI-mode store back to the ADVANCED test default (see
  // renderWithProviders.tsx's doc comment) and clear its localStorage mirror, so a test that called
  // setMode()/setUiModeForTests() directly (bypassing renderWithProviders) never leaks its mode
  // into the next test.
  setUiModeForTests("ADVANCED");
  try {
    window.localStorage.removeItem(UI_MODE_STORAGE_KEY);
  } catch {
    // jsdom's localStorage doesn't throw in practice - best-effort only, mirrors the app's own
    // fail-safe storage helpers (src/lib/uiMode/uiModeStorage.ts).
  }
});
afterAll(() => server.close());

// jsdom doesn't implement matchMedia; Mantine's components query it (e.g. color-scheme handling).
if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

// jsdom doesn't implement ResizeObserver either; Mantine's ScrollArea (used by Table.ScrollContainer
// in the import wizard's preview/mapping/validate tables) observes size changes with it. Floating-ui
// (which Popover/Combobox dropdowns — e.g. every Select in the import wizard — use for positioning)
// also relies on a ResizeObserver firing at least once to finish its first position computation and
// flip the dropdown's `display: none` off; a completely inert stub leaves dropdowns permanently
// hidden in jsdom (no real layout engine ever triggers a resize), so this stub invokes its callback
// once synchronously on observe().
if (typeof window !== "undefined" && !window.ResizeObserver) {
  class ResizeObserverStub {
    private readonly callback: ResizeObserverCallback;

    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }

    observe(target: Element): void {
      this.callback(
        [{ target } as unknown as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    }

    unobserve(): void {}
    disconnect(): void {}
  }
  window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

// jsdom doesn't implement scrollIntoView; Mantine's Combobox (Select's dropdown) calls it when
// keyboard/selection-driven navigation scrolls the active option into view.
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}

// jsdom has no real layout engine — every element's getBoundingClientRect() is all-zero. Mantine's
// Select/Combobox dropdown (a Popover) positions itself via floating-ui, which includes a `hide()`
// middleware that sets the dropdown's `display: none` whenever it thinks the reference element is
// clipped by a scrolling ancestor or the viewport — with every rect zeroed out, its overflow math
// degenerates to "fully clipped" every time, so the dropdown panel is always `display: none` in
// jsdom regardless of open state, even though its content (role="option" items etc.) is correctly
// in the DOM. This is a well-known jsdom limitation for floating-ui-based popovers/comboboxes, not
// a real bug — component tests that need to interact with an open Select's options pass
// `{ hidden: true }` to Testing Library's role queries to look past this cosmetic `display: none`
// (see MappingStep.test.tsx / ValidateStep.test.tsx).
