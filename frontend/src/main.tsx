// Bundled variable sans (v0.3.0 WI-6) - weight axis only (no italics needed), so the desktop shell
// never fetches a remote font (Tauri runs offline; CLAUDE.md's confidentiality/offline rules are
// absolute about not depending on network access at runtime).
import "@fontsource-variable/inter/wght.css";
import "@mantine/core/styles.css";
import "@mantine/notifications/styles.css";
import "@mantine/dates/styles.css";
import "@mantine/spotlight/styles.css";
import "./app/global.css";

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { router } from "./router";
import { theme } from "./app/theme";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="light">
      {/* top-right, not the Mantine default bottom-right: M4 adds a right-side Drawer
          (Deltagarvy detail) whose own action buttons live in its bottom-right corner - a
          bottom-right toast would visually stack on top of them (real usability issue, not just a
          test artifact - Playwright caught it via a genuine pointer-event interception). */}
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
);
