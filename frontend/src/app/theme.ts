import { createTheme, type MantineColorsTuple } from "@mantine/core";

/**
 * v0.3.0 WI-6 ("Gör även lite finare och modernare UI"): a coastal, professional look for the
 * council's tool - deep ocean-blue primary, warm sand as a sparing accent (badges, the live-solve
 * pulse, the waitlist card), warm-tinted neutrals instead of Mantine's default cool grays, a bundled
 * variable sans (main.tsx imports @fontsource-variable/inter - no CDN font, Tauri runs offline), and
 * slightly rounder/deeper components (defaultRadius "md", Card/Paper/Modal get a subtle shadow by
 * default). Light mode only - the app has no dark-mode requirement.
 */

// Anchored on #0e7490..#155e75 (a teal-leaning deep blue) at shades 7-8, which is what
// `primaryShade: 7` below actually puts on buttons/links/the active tab underline/etc.
const ocean: MantineColorsTuple = [
  "#eef8fa",
  "#dbeef2",
  "#b3dee6",
  "#87ccd9",
  "#5cb7c9",
  "#3aa1b7",
  "#1e8aa3",
  "#0e7490",
  "#155e75",
  "#0d3f4d",
];

// Warm sand/amber - used explicitly (color="sand") where the brief calls for a warm accent
// (badges, highlights, the live-solve improvement pulse, the waitlist card), never as the app's
// primary color.
const sand: MantineColorsTuple = [
  "#fdf8f0",
  "#faeed9",
  "#f3dbae",
  "#eac47e",
  "#e0ac52",
  "#d3922f",
  "#b8791e",
  "#8f5c18",
  "#6b4413",
  "#48300f",
];

// Replaces Mantine's default cool-toned gray (used for borders, `c="dimmed"` text, table
// stripes/headers, disabled states, ...) with a warm neutral (Tailwind "stone"-equivalent) so the
// whole app reads warmer, not just the accent color. Darker than Mantine's stock gray-6 at the
// "dimmed" shade, which also comfortably clears WCAG AA (>7:1 on white).
const warmGray: MantineColorsTuple = [
  "#fafaf9",
  "#f5f5f4",
  "#e7e5e4",
  "#d6d3d1",
  "#a8a29e",
  "#78716c",
  "#57534e",
  "#44403c",
  "#292524",
  "#1c1917",
];

export const theme = createTheme({
  primaryColor: "ocean",
  primaryShade: 7,
  colors: { ocean, sand, gray: warmGray },
  defaultRadius: "md",
  fontFamily:
    "'Inter Variable', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
  headings: {
    fontFamily: "'Inter Variable', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    fontWeight: "650",
  },
  components: {
    Card: {
      defaultProps: { withBorder: true, shadow: "xs" },
    },
    Paper: {
      defaultProps: { shadow: "xs" },
    },
    Modal: {
      defaultProps: { radius: "md", padding: "lg" },
    },
    Title: {
      styles: { root: { letterSpacing: "-0.01em" } },
    },
    // Denser, dimmed uppercase-ish header row for every data table (FieldsPanel,
    // ConstraintWeightsTable, the season/plan list tables, ...) - purely presentational, doesn't
    // touch header text/testids/HelpTips. Tables without a <Table.Thead> (e.g. GroupCard's compact
    // member list) are unaffected since they never render a `th`. Deliberately NOT `textTransform:
    // "uppercase"` despite the brief's "uppercase-ish" wording: Chromium's accessible-name
    // computation follows CSS text-transform ("NAMN" instead of "Namn" in the a11y tree), which risks
    // breaking a Playwright spec matching on sv.* header text - the e2e hard constraint (no aria-name
    // changes) wins over the literal "uppercase-ish" ask. Smaller/bold/tighter-tracking/dimmed still
    // reads as a denser, quieter header row.
    Table: {
      styles: {
        thead: { backgroundColor: "var(--mantine-color-gray-0)" },
        th: {
          fontSize: "var(--mantine-font-size-xs)",
          fontWeight: 700,
          letterSpacing: "0.02em",
          color: "var(--mantine-color-gray-6)",
        },
      },
    },
  },
});
