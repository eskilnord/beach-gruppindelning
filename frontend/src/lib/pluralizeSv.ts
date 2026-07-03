/**
 * "N word" with the Swedish singular/plural form picked by count - extracted from scoreFormat.ts
 * (WI-E review fix 5) so i18n/sv.ts can reuse the exact same helper for its own count-bearing
 * strings ("1 hårt brott" / "2 hårda brott", "1 grupp" / "2 grupper") without importing route code.
 */
export function pluralize(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
