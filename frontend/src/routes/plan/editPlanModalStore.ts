import { create } from "zustand";

interface EditPlanModalStoreState {
  opened: boolean;
  open: () => void;
  close: () => void;
}

/**
 * Tiny global store (zustand, same pattern as `components/tutorial/tutorialStore.ts`) for the
 * "Redigera aktivitetsplan" modal's open state. `PlanLayout` mounts the single `<EditPlanModal>`
 * instance and its own "Redigera plan" button; OptimizePanel's "Ändra…" link (next to the
 * "Standard: ..." group-defaults hint, v0.3.0) needs to open that SAME instance without prop-drilling
 * through the route tree, so both call `useEditPlanModalStore.getState().open()`.
 */
export const useEditPlanModalStore = create<EditPlanModalStoreState>((set) => ({
  opened: false,
  open: () => set({ opened: true }),
  close: () => set({ opened: false }),
}));
