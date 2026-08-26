import { useState } from "react";
import { Alert, Box, Button, FileButton, Group, Loader, Stack, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useCreateImportSession, type ImportAnalysis } from "../../../api/import";
import { sv } from "../../../i18n/sv";
import { cacheImportSheets } from "../importSessionStorage";
import { userErrorText } from "../userErrorText";

const ACCEPTED_EXTENSIONS = [".xlsx", ".csv"];
const LEGACY_EXCEL_EXTENSION = ".xls";

function hasAcceptedExtension(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

// Only meaningful once hasAcceptedExtension has already said no (a ".xlsx" file does NOT also match
// this suffix check - ".xlsx" ends in "lsx", not ".xls" - so call order doesn't actually matter, but
// this is only ever called from the rejected-extension branch below).
function isLegacyExcelFile(fileName: string): boolean {
  return fileName.toLowerCase().endsWith(LEGACY_EXCEL_EXTENSION);
}

interface FileStepProps {
  planId: string;
  onUploaded: (sessionId: string, analysis: ImportAnalysis) => void;
}

/** Wizard step 1 (spec §8.3): drag-drop or file-picker upload, .xlsx/.csv only. No @mantine/dropzone
 *  dependency (not in package.json / CLAUDE.md's pinned dependency list) — a plain HTML5 drag
 *  target plus Mantine's core FileButton covers both interactions. */
export function FileStep({ planId, onUploaded }: FileStepProps) {
  const [dragActive, setDragActive] = useState(false);
  // v0.6.0 audit-fix B5: a rejected .xls file gets a PERSISTENT message near the drop zone (naming
  // the actual filename) rather than a toast that can disappear before the admin reads it - cleared
  // on the next upload attempt, whatever its outcome.
  const [legacyXlsFileName, setLegacyXlsFileName] = useState<string | null>(null);
  const createSession = useCreateImportSession(planId);

  const handleFile = async (file: File | null) => {
    if (!file) {
      return;
    }
    setLegacyXlsFileName(null);
    if (!hasAcceptedExtension(file.name)) {
      if (isLegacyExcelFile(file.name)) {
        setLegacyXlsFileName(file.name);
        return;
      }
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
      onUploaded(created.sessionId, created.analysis);
    } catch (error) {
      // v0.6.0 audit-fix B5: a network failure (the request never reached the backend at all) reads
      // very differently from a parse failure (the backend received the file and rejected it, e.g. a
      // 400 for a corrupt/unreadable workbook) - userErrorText distinguishes the two; anything neither
      // (unexpected) falls back to this step's own existing, more specific "Kunde inte läsa in filen".
      const message = error instanceof TypeError ? sv.importWizard.networkError : userErrorText(error);
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: message === sv.importWizard.genericError ? sv.importWizard.file.uploadFailed : message,
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
      {legacyXlsFileName && (
        <Alert color="red" title={sv.importWizard.file.legacyXlsTitle(legacyXlsFileName)}>
          {sv.importWizard.file.legacyXlsMessage}
        </Alert>
      )}
    </Stack>
  );
}
