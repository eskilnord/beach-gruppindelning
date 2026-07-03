import type { ReactNode } from "react";
import { ActionIcon, Popover, Text } from "@mantine/core";

interface HelpTipProps {
  /** Accessible name for the trigger button (e.g. "Förklaring: Vikt") - announced by screen
   *  readers, never rendered as visible text itself. Keep it unique per usage so multiple HelpTips
   *  on the same page stay distinguishable to assistive tech. */
  label: string;
  /** The Swedish explanation shown in the popover. Kept short (1-3 sentences) per usage site. */
  children: ReactNode;
}

/**
 * Small round info-icon button that reveals a short Swedish explanation on click (v0.3.0 WI-3,
 * user feedback: "Förbättra användarvänligheten genom att förklara vad olika inställningar
 * innebär."). Deliberately a `Popover` rather than a `Tooltip`: it must work on tap (no hover) and
 * comfortably hold multi-sentence copy without auto-dismissing while the user is still reading it.
 *
 * ADDITIVE ONLY (e2e safety, v0.3.0 ground rules): this component must never be nested inside an
 * existing Mantine `label`/`description`-linked element whose accessible name a Playwright spec
 * asserts via `getByLabel`/`getByRole(..., { name })` - see call sites for the two safe patterns
 * used throughout: (1) as a sibling of a heading/column-header text inside a wrapping `Group`, or
 * (2) passed as an input's `description` prop (never its `label`), which only ever ADDS a new
 * `aria-describedby` relationship rather than altering the input's existing accessible name.
 *
 * No icon library is installed in this repo (`package.json` has no `@tabler/icons-react` or
 * similar) - a plain "ⓘ" glyph keeps this dependency-free.
 */
export function HelpTip({ label, children }: HelpTipProps) {
  return (
    // trapFocus (not just closeOnEscape, which Mantine only wires up as a keydown-capture handler
    // ON THE DROPDOWN ITSELF): without it, focus stays on the trigger button after opening and an
    // Escape press never reaches the dropdown's own listener, so the popover would only ever close
    // via a click outside. Trapping focus into the dropdown on open both satisfies "Escape closes"
    // and lets screen-reader/keyboard users actually reach the explanation text.
    <Popover width={320} withArrow shadow="md" withinPortal={false} closeOnEscape trapFocus>
      <Popover.Target>
        <ActionIcon
          type="button"
          variant="subtle"
          color="gray"
          size="xs"
          radius="xl"
          aria-label={label}
          component="button"
        >
          <Text size="xs" fw={700} span style={{ lineHeight: 1 }} aria-hidden="true">
            ⓘ
          </Text>
        </ActionIcon>
      </Popover.Target>
      {/* data-mantine-stop-propagation (review fix): several HelpTips sit inside Mantine Modals,
          whose closeOnEscape listens on window keydown in the CAPTURE phase (ModalBase
          use-modal.mjs) - element-level stopPropagation can never beat that, so an Escape meant
          for this popover would dismiss the whole modal too. Mantine's own opt-out is this data
          attribute checked on `event.target`: trapFocus keeps focus ON the dropdown while open,
          so tagging the dropdown suppresses the modal exactly while the popover is open (its own
          closeOnEscape still closes it) and never otherwise - the same convention Mantine's
          Menu/Combobox dropdowns use. Pinned by the Modal regression tests in HelpTip.test.tsx. */}
      <Popover.Dropdown data-mantine-stop-propagation="true">
        <Text size="sm">{children}</Text>
      </Popover.Dropdown>
    </Popover>
  );
}
