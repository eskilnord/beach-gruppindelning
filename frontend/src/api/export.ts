import { useMutation } from "@tanstack/react-query";
import { apiDownload } from "./client";
import { saveFile } from "../lib/platform";
import type { ExportFormat, ExportLayout } from "./types";

export interface ExportPlanRequest {
  format: ExportFormat;
  layout: ExportLayout;
  includeComments: boolean;
}

/** "Exportera" (spec §20): downloads the plan's export file and hands it to platform.ts's
 *  saveFile (anchor download in browser, native save dialog in Tauri). Not a `useQuery` - each click
 *  is a one-shot download, nothing to cache/invalidate. Resolves to whether the file was actually
 *  written (false if the user cancelled the Tauri save dialog). */
export function useExportPlan(planId: string) {
  return useMutation({
    mutationFn: async ({ format, layout, includeComments }: ExportPlanRequest) => {
      const params = new URLSearchParams({ format, layout, includeComments: String(includeComments) });
      const { blob, filename } = await apiDownload(`/api/plans/${planId}/export?${params.toString()}`, "export");
      return saveFile(blob, filename);
    },
  });
}

/** "Anonymiserat testdata" (spec §21.3) - no `includeComments`/`layout` choice, comments are never
 *  included and the layout is always the anonymized service's own flat schema. */
export function useExportAnonymized(planId: string) {
  return useMutation({
    mutationFn: async (format: ExportFormat) => {
      const { blob, filename } = await apiDownload(
        `/api/plans/${planId}/export/anonymized?format=${format}`,
        "anonymiserat",
      );
      return saveFile(blob, filename);
    },
  });
}
