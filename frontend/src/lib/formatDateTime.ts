/**
 * Shared "sv-SE" short date+time formatter (spec §19.9's own "Senaste körning" style, e.g. an
 * OptimizationRun's `startedAt`) - used anywhere a run timestamp is shown to the user: the
 * Optimeringsvy's last-run card, the Resultatvy's "Förklaringar baserade på senaste körning" line,
 * and the M7 explain-drawer staleness banner.
 */
export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString("sv-SE", { dateStyle: "short", timeStyle: "short" });
}
