import { sv } from "../../../../i18n/sv";

export interface StalenessBanner {
  show: boolean;
  message: string;
}

/**
 * Explain-drawer/what-if staleness banner logic (task M7 UX brief; docs/design/04-solver.md §11.6
 * staleness envelope): shown whenever an explanation response's own `stale` flag is true - the plan
 * has been mutated (manual move, lock/unlock, ...) since the run it explains was solved. Content is
 * still shown underneath regardless (per the brief: "content still shown").
 *
 * Wording nuance (M7 review): the backend RE-COMPUTES every explanation from the plan's CURRENT
 * persisted state - a stale explanation is NOT the old run's historical data replayed, so the banner
 * must not say "baserad på körning från {tid}". What's outdated is the RUN the explanation is keyed
 * to; the explanation itself reflects the current state, and re-solving is what restores a
 * run-matching explanation. Hence no run timestamp in the message at all.
 */
export function describeStaleness(stale: boolean): StalenessBanner {
  if (!stale) {
    return { show: false, message: "" };
  }
  return { show: true, message: sv.results.explain.staleBanner };
}
