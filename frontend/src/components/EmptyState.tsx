import type { ReactNode } from "react";
import { Stack, Text, ThemeIcon } from "@mantine/core";

interface EmptyStateProps {
  /** Decorative only (aria-hidden by ThemeIcon's own rendering of an icon component) - the message
   *  text below is what screen readers announce, unchanged from the plain `<Text c="dimmed">` this
   *  replaces. */
  icon: ReactNode;
  message: string;
  /** Existing CTA (e.g. StartPage's demo-data button, ResultsPanel's "go to Optimering" button),
   *  rendered as-is below the message - this component never invents new actions. */
  action?: ReactNode;
}

/**
 * v0.3.0 WI-6: small reusable empty-state block - a muted icon in a soft circle + a one-line message
 * (still sourced from sv.ts) + an optional existing call-to-action. Replaces the bare
 * `<Text c="dimmed">…</Text>` empty states across start/season/participants/resources/coaches/
 * capacity/results/saved-plans with something a little friendlier than a lone gray sentence, without
 * changing any copy, aria attributes, or data-testids.
 */
export function EmptyState({ icon, message, action }: EmptyStateProps) {
  return (
    <Stack align="center" gap="xs" py="xl">
      <ThemeIcon size={44} radius="xl" variant="light" color="gray">
        {icon}
      </ThemeIcon>
      <Text c="dimmed" ta="center" maw={360}>
        {message}
      </Text>
      {action}
    </Stack>
  );
}
