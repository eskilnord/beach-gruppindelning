import { useRef, useState, type ReactNode } from "react";
import { ActionIcon, Popover, Text, Tooltip } from "@mantine/core";

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
 * innebär."). Deliberately a `Popover` rather than a `Tooltip` for the CLICK path: it must work on
 * tap (no hover) and comfortably hold multi-sentence copy without auto-dismissing while the user is
 * still reading it.
 *
 * v0.6.0 audit-fix A9 (walkthrough finding: the icon rendered as a stray, click-only 12×12 glyph on
 * its own line): the trigger is now a real 24px hit target (min touch-target size), and a hover
 * `Tooltip` previews the same explanation for a mouse user who'd otherwise need to click just to
 * find out whether the icon is worth opening. The hover Tooltip is wired via Mantine's `target` ref
 * mode (NOT nested between `Popover.Target` and the button) - `Tooltip`'s normal `cloneElement`
 * composition only forwards a curated prop subset to its child, so nesting it there would silently
 * swallow the aria-haspopup/aria-expanded/aria-controls/id that `Popover.Target` clones onto the
 * button; anchoring via `target` instead keeps the button's DOM untouched by Tooltip entirely. The
 * hover preview is suppressed while the popover itself is open (`disabled={popoverOpened}`) so the
 * two floating explanations of the same text never show at once.
 *
 * ADDITIVE ONLY (e2e safety, v0.3.0 ground rules): this component must never be nested inside an
 * existing Mantine `label`/`description`-linked element whose accessible name a Playwright spec
 * asserts via `getByLabel`/`getByRole(..., { name })` - see call sites for the two safe patterns
 * used throughout: (1) as a sibling of a heading/column-header text inside a wrapping `Group`, or
 * (2) passed as an input's `description` prop (never its `label`), which only ever ADDS a new
 * `aria-describedby` relationship rather than altering the input's existing accessible name. (A
 * `<button>` is a labelable element - nesting one inside a real `<label>` is invalid HTML and would
 * make the label's OWN click-forwarding behavior collide with the button's; callers that want the
 * icon visually beside a field's label should render the label as plain text ABOVE the input
 * instead, pattern (1) above, with an explicit `aria-label` on the input to keep its accessible name
 * intact - see CreatePlanModal.tsx's Kategori/Grupptyp field.)
 */
export function HelpTip({ label, children }: HelpTipProps) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [popoverOpened, setPopoverOpened] = useState(false);

  return (
    <>
      <Popover
        width={320}
        withArrow
        shadow="md"
        withinPortal={false}
        closeOnEscape
        trapFocus
        onChange={setPopoverOpened}
      >
        <Popover.Target>
          <ActionIcon
            ref={triggerRef}
            type="button"
            variant="subtle"
            color="gray"
            size={24}
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
      <Tooltip
        target={triggerRef}
        disabled={popoverOpened}
        multiline
        w={260}
        withArrow
        openDelay={250}
        label={<Text size="xs">{children}</Text>}
      />
    </>
  );
}
