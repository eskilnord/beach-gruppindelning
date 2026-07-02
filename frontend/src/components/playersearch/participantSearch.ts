/**
 * Case- and diacritics-insensitive normalization for the Ctrl/Cmd+F player search (spec: typing
 * "asa" must find "Åsa"). Unicode NFD splits base letters from their combining diacritical marks
 * (U+0300-U+036F), which are then stripped - works for Swedish å/ä/ö and any other accented Latin
 * letters (é, ü, ...) without a hardcoded character map.
 */
const COMBINING_DIACRITICAL_MARKS = /[̀-ͯ]/g;

export function normalizeForSearch(value: string): string {
  return value
    .normalize("NFD")
    .replace(COMBINING_DIACRITICAL_MARKS, "")
    .toLowerCase()
    .trim();
}

/**
 * True when `query` (diacritics- and case-insensitive) is a substring of `name`. An empty/blank
 * query matches everything - the Spotlight's initial "show every participant" state before the user
 * types anything.
 */
export function matchesSearchQuery(name: string, query: string): boolean {
  const normalizedQuery = normalizeForSearch(query);
  if (normalizedQuery.length === 0) {
    return true;
  }
  return normalizeForSearch(name).includes(normalizedQuery);
}
