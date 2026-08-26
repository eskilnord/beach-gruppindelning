import { useIsSimpleMode } from "../../../lib/uiMode/useUiMode";
import { OptimizePanel } from "./OptimizePanel";
import { OptimizePanelSimple } from "./OptimizePanelSimple";

/**
 * v0.6.0 F4 (M-S4): the mode switch for the "optimering" route (router.tsx) - ADVANCED renders the
 * full OptimizePanel unchanged (byte-identical, per this milestone's brief), SIMPLE renders the
 * reduced OptimizePanelSimple. Kept as its own tiny component (rather than an inline ternary in
 * router.tsx) so router.tsx's route table stays a flat list of one element per path, matching every
 * other entry there.
 */
export function OptimizeRoute() {
  const isSimple = useIsSimpleMode();
  return isSimple ? <OptimizePanelSimple /> : <OptimizePanel />;
}
