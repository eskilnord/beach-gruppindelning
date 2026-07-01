import { Card, Text, Title } from "@mantine/core";
import { sv } from "../../i18n/sv";

interface PlaceholderPanelProps {
  title: string;
}

/** Empty placeholder for a plan tab not yet implemented (M2 scope: shell + navigation only). */
export function PlaceholderPanel({ title }: PlaceholderPanelProps) {
  return (
    <Card withBorder padding="xl">
      <Title order={4} mb="xs">
        {title}
      </Title>
      <Text c="dimmed">{sv.plan.comingSoon}</Text>
    </Card>
  );
}
