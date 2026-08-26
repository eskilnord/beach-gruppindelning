import type { ReactElement, ReactNode } from "react";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { render } from "@testing-library/react";
import { setUiModeForTests } from "../lib/uiMode/uiModeStore";
import type { UiMode } from "../lib/uiMode/uiMode";

/** Fresh QueryClient per render call: no retries/caching noise across assertions. */
function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>
  );
}

interface RenderWithProvidersOptions {
  /** Overrides the global UI mode for this render. Defaults to ADVANCED - deliberately NOT the
   *  product default (SIMPLE, see src/lib/uiMode/uiMode.ts's DEFAULT_UI_MODE) - so every spec
   *  written before v0.6.0's SIMPLE/ADVANCED split (i.e. against "all panels/tabs always visible")
   *  keeps passing unchanged; only specs that actually exercise mode-gated behavior need to pass
   *  `uiMode: "SIMPLE"` explicitly. */
  uiMode?: UiMode;
  /** v0.6.0 F6 review fix (FIX 3, MAJOR): overrides uiModeStore's `reconciled` flag for this render.
   *  Defaults to true (see setUiModeForTests's own doc comment) - pass `false` only for a spec that
   *  deliberately mounts `<UiModeSync/>` itself and wants to observe pre-reconcile state (e.g.
   *  UiModeIntroBanner.test.tsx's own reconciliation-timing tests). */
  reconciled?: boolean;
}

export function renderWithProviders(ui: ReactElement, options: RenderWithProvidersOptions = {}) {
  setUiModeForTests(options.uiMode ?? "ADVANCED", { reconciled: options.reconciled });
  return render(ui, { wrapper: Providers });
}
