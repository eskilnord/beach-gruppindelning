import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShellLayout } from "./components/AppShellLayout";
import { StartPage } from "./routes/start/StartPage";
import { SeasonPage } from "./routes/season/SeasonPage";
import { PlanLayout } from "./routes/plan/PlanLayout";
import { ParticipantsPanel } from "./routes/plan/participants/ParticipantsPanel";
import { FieldsPanel } from "./routes/plan/fields/FieldsPanel";
import { ResourcesPanel } from "./routes/plan/resources/ResourcesPanel";
import { CoachesPanel } from "./routes/plan/coaches/CoachesPanel";
import { CapacityPanel } from "./routes/plan/capacity/CapacityPanel";
import { OptimizePanel } from "./routes/plan/optimize/OptimizePanel";
import { ResultsPanel } from "./routes/plan/results/ResultsPanel";
import { SavedPlansPanel } from "./routes/plan/savedplans/SavedPlansPanel";
import { ExportPanel } from "./routes/plan/export/ExportPanel";
import { ImportWizardPage } from "./routes/import/ImportWizardPage";

/**
 * Route tree (docs/design/02-product-data-ui.md §6, corrected paths): Startvy at "/", Säsongsvy at
 * "/seasons/:seasonId", plan layout with tab navigation at "/plans/:planId/<tab>". Optimering/
 * Resultat (M6b) render OptimizePanel/ResultsPanel (Resultatvy includes the Planeringskarta as a
 * Kort/Schema sub-view toggle, spec §19.11). M8 adds "planer" (SavedPlansPanel, spec §14) and wires
 * the real ExportPanel (spec §20/§21.3) in place of the former PlaceholderPanel.
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
          { path: "planer", element: <SavedPlansPanel /> },
          { path: "export", element: <ExportPanel /> },
        ],
      },
    ],
  },
]);
