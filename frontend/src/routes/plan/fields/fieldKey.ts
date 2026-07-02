/**
 * Derives a field_definition `key` from a user-typed label (Fältbyggaren "Nytt fält" modal, spec
 * §9.1). Mirrors the backend's key grammar exactly (`FieldDefinitionController.KEY_PATTERN`:
 * `^[a-zA-Z][a-zA-Z0-9]*$`, i.e. camelCase, letters/digits only, starting with a letter) so the
 * generated key never gets rejected by the create endpoint.
 *
 * Diacritics (å/ä/ö and any other accented Latin letter, e.g. é) are transliterated via Unicode
 * NFD decomposition + stripping combining marks - å -> a, ä -> a, ö -> o.
 */
export function generateFieldKey(label: string): string {
  const transliterated = label
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, ""); // strip combining diacritical marks (NFD)

  const words = transliterated.split(/[^a-zA-Z0-9]+/).filter((word) => word.length > 0);

  if (words.length === 0) {
    return "field";
  }

  const key = words
    .map((word, index) => {
      const lower = word.toLowerCase();
      if (index === 0) {
        return /^[0-9]/.test(lower) ? lower : lower.charAt(0) + lower.slice(1);
      }
      return lower.charAt(0).toUpperCase() + lower.slice(1);
    })
    .join("");

  // The key must start with a letter (spec/backend KEY_PATTERN) - a label starting with a digit
  // (e.g. "2026 Deltagare") would otherwise produce "2026Deltagare".
  return /^[a-zA-Z]/.test(key) ? key : `field${key}`;
}
