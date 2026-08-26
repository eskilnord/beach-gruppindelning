import type { ReactNode } from "react";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";

interface ModeGatedProps {
  children: ReactNode;
  /** Rendered instead of `children` while the gate doesn't match. Defaults to nothing. */
  fallback?: ReactNode;
}

/**
 * Renders `children` only in ADVANCED mode; in SIMPLE mode renders `fallback` (default: nothing).
 *
 * v0.6.0 F1 (M-S1) introduces the plumbing only - simple mode still shows all 9 plan tabs this
 * milestone, with only the four routes wrapped in <AdvancedRouteGate> gated. AdvancedOnly/SimpleOnly
 * aren't wired into any panel yet; they exist for later information-architecture milestones to use
 * inside panels (e.g. hiding an advanced-only field or section without gating the whole route).
 */
export function AdvancedOnly({ children, fallback = null }: ModeGatedProps) {
  const isSimple = useIsSimpleMode();
  return <>{isSimple ? fallback : children}</>;
}

/** Renders `children` only in SIMPLE mode; in ADVANCED mode renders `fallback` (default: nothing).
 *  See {@link AdvancedOnly}'s doc comment. */
export function SimpleOnly({ children, fallback = null }: ModeGatedProps) {
  const isSimple = useIsSimpleMode();
  return <>{isSimple ? children : fallback}</>;
}
