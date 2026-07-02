import { create } from "zustand";

interface TutorialStoreState {
  opened: boolean;
  open: () => void;
  close: () => void;
}

/**
 * Tiny global store (zustand, already a pinned dependency - CLAUDE.md) for the tutorial modal's open
 * state. Lets both the persistent "?" affordance in AppShellLayout's header and the one-time
 * "Ny här?" banner on Startvy (TutorialBanner.tsx) open the SAME modal instance without prop-drilling
 * through the route tree: AppShellLayout mounts the one <TutorialModal>, everyone else just calls
 * `useTutorialStore.getState().open()` (or the hook, for reactive reads).
 */
export const useTutorialStore = create<TutorialStoreState>((set) => ({
  opened: false,
  open: () => set({ opened: true }),
  close: () => set({ opened: false }),
}));
