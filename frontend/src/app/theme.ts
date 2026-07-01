import { createTheme } from "@mantine/core";

/**
 * Sober, internal-tool styling: Mantine defaults with consistent spacing and a calm primary color.
 * No exotic design work needed per the M2 brief — this just keeps the defaults from looking
 * unbranded.
 */
export const theme = createTheme({
  primaryColor: "blue",
  defaultRadius: "sm",
  fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
});
