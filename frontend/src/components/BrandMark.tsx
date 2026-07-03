/**
 * v0.3.0 WI-6: small inline-SVG brand mark for the app header - an abstract volleyball/wave roundel
 * in the ocean-blue primary with a sand accent, so the header reads as this club's tool rather than
 * a generic admin template. Deliberately not an external asset (Tauri runs offline, CLAUDE.md) - a
 * handful of inline shapes instead.
 */
export function BrandMark() {
  return (
    <svg
      width="26"
      height="26"
      viewBox="0 0 26 26"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="13" cy="13" r="12" fill="var(--mantine-color-ocean-7)" />
      <path
        d="M1.5 10.5c3.6-4.6 8-6.9 11.5-6.9s7.9 2.3 11.5 6.9"
        stroke="var(--mantine-color-sand-4)"
        strokeWidth="1.6"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M1.2 14.5c3.6 4.6 8 6.9 11.5 6.9s7.9-2.3 11.5-6.9"
        stroke="white"
        strokeOpacity="0.6"
        strokeWidth="1.4"
        strokeLinecap="round"
        fill="none"
      />
      <circle cx="13" cy="13" r="2.1" fill="var(--mantine-color-sand-4)" />
    </svg>
  );
}
