import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShellLayout } from "./components/AppShellLayout";
import { StartPage } from "./routes/start/StartPage";
import { SeasonPage } from "./routes/season/SeasonPage";
import { PlanLayout } from "./routes/plan/PlanLayout";
import { PlaceholderPanel } from "./routes/plan/PlaceholderPanel";
import { ParticipantsPanel } from "./routes/plan/participants/ParticipantsPanel";
import { FieldsPanel } from "./routes/plan/fields/FieldsPanel";
import { ResourcesPanel } from "./routes/plan/resources/ResourcesPanel";
import { CoachesPanel } from "./routes/plan/coaches/CoachesPanel";
import { CapacityPanel } from "./routes/plan/capacity/CapacityPanel";
import { OptimizePanel } from "./routes/plan/optimize/OptimizePanel";
import { ResultsPanel } from "./routes/plan/results/ResultsPanel";
import { ImportWizardPage } from "./routes/import/ImportWizardPage";
import { sv } from "./i18n/sv";

/**
 * Route tree (docs/design/02-product-data-ui.md §6, corrected paths): Startvy at "/", Säsongsvy at
 * "/seasons/:seasonId", plan layout with tab navigation at "/plans/:planId/<tab>". Optimering/
 * Resultat (M6b) render OptimizePanel/ResultsPanel (Resultatvy includes the Planeringskarta as a
 * Kort/Schema sub-view toggle, spec §19.11). Export (M8+) still renders an empty PlaceholderPanel
 * with a "kommer i senare milstolpe" note.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShellLayout />,
    children: [
      { index: true, element: <StartPage /> },
      { path: "seasons/:seasonId", element: <SeasonPage /> },
      { path: "plans/:planId/import", element: <ImportWizardPage /> },
      {
        path: "plans/:planId",
        element: <PlanLayout />,
        children: [
          { index: true, element: <Navigate to="deltagare" replace /> },
          { path: "deltagare", element: <ParticipantsPanel /> },
          { path: "falt", element: <FieldsPanel /> },
          { path: "resurser", element: <ResourcesPanel /> },
          { path: "tranare", element: <CoachesPanel /> },
          { path: "kapacitet", element: <CapacityPanel /> },
          { path: "optimering", element: <OptimizePanel /> },
          { path: "resultat", element: <ResultsPanel /> },
          { path: "export", element: <PlaceholderPanel title={sv.plan.tabs.export} /> },
        ],
      },
    ],
  },
]);
