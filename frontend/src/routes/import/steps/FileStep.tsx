import { useState } from "react";
import { Box, Button, FileButton, Group, Loader, Stack, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useCreateImportSession } from "../../../api/import";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { cacheImportSheets } from "../importSessionStorage";

const ACCEPTED_EXTENSIONS = [".xlsx", ".csv"];

function hasAcceptedExtension(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

interface FileStepProps {
  planId: string;
  onUploaded: (sessionId: string) => void;
}

/** Wizard step 1 (spec §8.3): drag-drop or file-picker upload, .xlsx/.csv only. No @mantine/dropzone
 *  dependency (not in package.json / CLAUDE.md's pinned dependency list) — a plain HTML5 drag
 *  target plus Mantine's core FileButton covers both interactions. */
export function FileStep({ planId, onUploaded }: FileStepProps) {
  const [dragActive, setDragActive] = useState(false);
  const createSession = useCreateImportSession(planId);

  const handleFile = async (file: File | null) => {
    if (!file) {
      return;
    }
    if (!hasAcceptedExtension(file.name)) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: sv.importWizard.file.invalidType,
      });
      return;
    }
    try {
      const created = await createSession.mutateAsync(file);
      cacheImportSheets(created.sessionId, created.sheets);
      onUploaded(created.sessionId);
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.file.uploadFailed,
      });
    }
  };

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.file.heading}</Title>
      <Box
        onDragOver={(event) => {
          event.preventDefault();
          setDragActive(true);
        }}
        onDragLeave={() => setDragActive(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragActive(false);
          const file = event.dataTransfer.files[0];
          void handleFile(file ?? null);
        }}
        style={{
          border: `2px dashed var(--mantine-color-${dragActive ? "blue-5" : "gray-4"})`,
          borderRadius: "var(--mantine-radius-md)",
          padding: "3rem 1.5rem",
          textAlign: "center",
          backgroundColor: dragActive ? "var(--mantine-color-blue-0)" : undefined,
          transition: "background-color 100ms ease",
        }}
      >
        {createSession.isPending ? (
          <Group justify="center" gap="xs">
            <Loader size="sm" />
            <Text>{sv.importWizard.file.uploading}</Text>
          </Group>
        ) : (
          <Stack align="center" gap="sm">
            <Text c="dimmed">{sv.importWizard.file.dropHint}</Text>
            <FileButton onChange={handleFile} accept=".xlsx,.csv">
              {(props) => (
                <Button variant="light" {...props}>
                  {sv.importWizard.file.pickButton}
                </Button>
              )}
            </FileButton>
          </Stack>
        )}
      </Box>
    </Stack>
  );
}
