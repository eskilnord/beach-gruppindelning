import { Alert, Table, Text, Title } from "@mantine/core";
import { sv } from "../../../../i18n/sv";
import type { WaitlistView } from "../../../../api/types";

interface WaitlistNarrativeProps {
  waitlist: WaitlistView;
  /** v0.6.0 F5 review fix (minor, waitlist heading level): ExplainDrawerBody's ADVANCED body always
   *  has its OWN top-level heading before this component renders (e.g. "Vald grupp"'s order-5
   *  sibling), so this component's own heading is a same-level SECTION heading there (order 5,
   *  the default). SimpleExplainBody has no such sibling - when `selectedGroup` is null this
   *  component substitutes for its own top-level headline (order 4), so it passes `headingOrder={4}`
   *  to keep that slot's heading level consistent regardless of which branch renders into it. */
  headingOrder?: 4 | 5;
}

/**
 * The waitlist narrative (kravspec §17.1/§17.2's waitlist branch of the person-level explain drawer,
 * `PersonExplanationResponse.waitlist` when `selectedGroup` is null): the server-rendered `reasonSv`
 * sentence, an optional "förbättring möjlig" quality-warning callout, and a per-group hard-blocker
 * table. Shared verbatim between ExplainDrawer.tsx's ADVANCED body and SimpleExplainBody.tsx (v0.6.0
 * F5) - extracted here rather than duplicated so both modes render the exact same waitlist content
 * (already plain-language, nothing to simplify further for SIMPLE).
 */
export function WaitlistNarrative({ waitlist, headingOrder = 5 }: WaitlistNarrativeProps) {
  return (
    <div data-testid="explain-waitlist-narrative">
      <Title order={headingOrder}>{sv.results.waitlist.heading}</Title>
      <Text size="sm">{waitlist.reasonSv}</Text>
      {waitlist.qualityWarningSv && (
        <Alert color="blue" title={sv.results.explain.waitlist.qualityWarningTitle} mt="xs">
          {waitlist.qualityWarningSv}
        </Alert>
      )}
      {waitlist.perGroupBlockers.length > 0 && (
        <>
          <Text size="sm" fw={500} mt="sm">
            {sv.results.explain.waitlist.blockersHeading}
          </Text>
          <Table verticalSpacing={4} withTableBorder mt={4}>
            <Table.Tbody>
              {waitlist.perGroupBlockers.map((blocker) => (
                <Table.Tr key={blocker.groupId}>
                  <Table.Td>{blocker.name}</Table.Td>
                  <Table.Td>{blocker.blockerSv}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </>
      )}
    </div>
  );
}
