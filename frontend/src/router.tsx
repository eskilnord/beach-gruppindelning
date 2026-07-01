import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShellLayout } from "./components/AppShellLayout";
import { StartPage } from "./routes/start/StartPage";
import { SeasonPage } from "./routes/season/SeasonPage";
import { PlanLayout } from "./routes/plan/PlanLayout";
import { PlaceholderPanel } from "./routes/plan/PlaceholderPanel";
import { sv } from "./i18n/sv";

/**
 * Route tree (docs/design/02-product-data-ui.md §6, corrected paths): Startvy at "/", Säsongsvy at
 * "/seasons/:seasonId", plan layout with tab navigation at "/plans/:planId/<tab>". Every tab beyond
 * this milestone's scope renders an empty PlaceholderPanel with a "kommer i senare milstolpe" note.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShellLayout />,
    children: [
      { index: true, element: <StartPage /> },
      { path: "seasons/:seasonId", element: <SeasonPage /> },
      {
        path: "plans/:planId",
        element: <PlanLayout />,
        children: [
          { index: true, element: <Navigate to="deltagare" replace /> },
          { path: "deltagare", element: <PlaceholderPanel title={sv.plan.tabs.participants} /> },
          { path: "falt", element: <PlaceholderPanel title={sv.plan.tabs.fields} /> },
          { path: "resurser", element: <PlaceholderPanel title={sv.plan.tabs.resources} /> },
          { path: "tranare", element: <PlaceholderPanel title={sv.plan.tabs.coaches} /> },
          { path: "kapacitet", element: <PlaceholderPanel title={sv.plan.tabs.capacity} /> },
          { path: "optimering", element: <PlaceholderPanel title={sv.plan.tabs.optimize} /> },
          { path: "resultat", element: <PlaceholderPanel title={sv.plan.tabs.results} /> },
          { path: "export", element: <PlaceholderPanel title={sv.plan.tabs.export} /> },
        ],
      },
    ],
  },
]);
