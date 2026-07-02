import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";

/**
 * Types + query hooks for the M3 import wizard REST surface, mounted under
 * `/api/plans/{planId}/import/...` — source of truth:
 * `backend/src/main/java/se/klubb/groupplanner/api/ImportController.java` and the `importer/`
 * package DTOs (`ColumnMapping`, `RowValidationResult`, `RowDecision`, `MappingTargetKind`,
 * `CommitResult`, `ImportSessionService`), see `backend/docs/m3-notes.md` for the full rationale.
 *
 * Deliberately hand-rolled rather than aliased from `components["schemas"]` in `schema.d.ts` like
 * the rest of `src/api/*.ts`: these DTOs are nested static records inside `ImportController`
 * (`ColumnInfo`, `DecisionDto`, ...) plus enum-valued fields (`RowStatus`, `MatchBasis`), and
 * springdoc-openapi's generated component names for that shape are harder to trust across
 * regenerations than mirroring the plain-Jackson-serialized Java records directly. `npm run
 * typegen` still regenerates schema.d.ts (so the endpoints are typed there too); these types are
 * cross-checked against it but not derived from it.
 */

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

export interface ImportSheetSummary {
  name: string;
  rowCount: number;
  suggestedTemplateId: string | null;
  suggestedTemplateName: string | null;
}

export interface CreatedImportSession {
  sessionId: string;
  sheets: ImportSheetSummary[];
}

export interface ImportPreview {
  sheet: string;
  headerRowIndex: number;
  rowCount: number;
  rows: string[][];
}

export interface ImportColumnInfo {
  columnIndex: number;
  headerText: string;
  sampleValues: string[];
  suggestedTarget: string | null;
}

export interface ImportColumns {
  sheet: string;
  headerRowIndex: number;
  columns: ImportColumnInfo[];
}

export interface ImportColumnMapping {
  columnIndex: number;
  /** One of MappingTargetKind's wire names (e.g. "firstName", "ignore") or "customField:<key>". */
  target: string;
}

export type RowStatus = "OK" | "WARN" | "SKIP";

export type MatchBasis = "EXTERNAL_ID_EXACT" | "EMAIL_EXACT" | "PHONE_EXACT" | "NAME_EXACT" | "NAME_SIMILAR";

export interface PersonMatchProposal {
  existingPersonId: string;
  matchBasis: MatchBasis;
  confidence: number;
}

export interface RowValidationResult {
  rowIndex: number;
  status: RowStatus;
  reasons: string[];
  matchProposals: PersonMatchProposal[];
}

export interface ImportValidation {
  rows: RowValidationResult[];
  totalRows: number;
  okCount: number;
  warnCount: number;
  skipCount: number;
}

export type ImportDecisionAction = "CREATE_NEW" | "MATCH_EXISTING" | "SKIP";

export interface ImportRowDecision {
  action: ImportDecisionAction;
  personId?: string;
}

export interface ImportCommitResult {
  imported: number;
  skipped: number;
  warnings: string[];
  importRunId: string;
  savedTemplateId: string | null;
}

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

const importSessionKey = (sessionId: string) => ["import", sessionId] as const;
const importColumnsKey = (sessionId: string) => ["import", sessionId, "columns"] as const;
const importPreviewKey = (sessionId: string, sheet: string, rows: number) =>
  ["import", sessionId, "preview", sheet, rows] as const;
const importValidationKey = (sessionId: string) => ["import", sessionId, "validate"] as const;

// ---------------------------------------------------------------------------
// Step 1 — Välj fil
// ---------------------------------------------------------------------------

export function useCreateImportSession(planId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return api.upload<CreatedImportSession>(`/api/plans/${planId}/import/sessions`, formData);
    },
  });
}

// ---------------------------------------------------------------------------
// Step 2 — Välj blad & granska
// ---------------------------------------------------------------------------

export function useImportPreview(planId: string, sessionId: string, sheet: string | null, rows = 30) {
  return useQuery({
    queryKey: importPreviewKey(sessionId, sheet ?? "", rows),
    queryFn: () =>
      api.get<ImportPreview>(
        `/api/plans/${planId}/import/sessions/${sessionId}/preview?sheet=${encodeURIComponent(sheet ?? "")}&rows=${rows}`,
      ),
    enabled: sheet !== null,
  });
}

export function useSetImportHeader(planId: string, sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { sheet: string; headerRowIndex: number }) =>
      api.put<{ sheet: string; headerRowIndex: number }>(
        `/api/plans/${planId}/import/sessions/${sessionId}/header`,
        body,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: importSessionKey(sessionId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Step 3 — Mappa kolumner
// ---------------------------------------------------------------------------

export function useImportColumns(planId: string, sessionId: string) {
  return useQuery({
    queryKey: importColumnsKey(sessionId),
    queryFn: () => api.get<ImportColumns>(`/api/plans/${planId}/import/sessions/${sessionId}/columns`),
  });
}

export function useSetImportMapping(planId: string, sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { sheet: string; mappings: ImportColumnMapping[] }) =>
      api.put<{ sheet: string; mappings: ImportColumnMapping[] }>(
        `/api/plans/${planId}/import/sessions/${sessionId}/mapping`,
        body,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: importSessionKey(sessionId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Step 4 — Validera
// ---------------------------------------------------------------------------

export function useImportValidation(planId: string, sessionId: string) {
  return useQuery({
    queryKey: importValidationKey(sessionId),
    queryFn: () => api.get<ImportValidation>(`/api/plans/${planId}/import/sessions/${sessionId}/validate`),
  });
}

export function useSetImportDecisions(planId: string, sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (decisions: Record<string, ImportRowDecision>) =>
      api.put<Record<string, ImportRowDecision>>(
        `/api/plans/${planId}/import/sessions/${sessionId}/decisions`,
        decisions,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: importValidationKey(sessionId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Step 5 — Importera
// ---------------------------------------------------------------------------

export function useCommitImport(planId: string, sessionId: string) {
  return useMutation({
    mutationFn: (body: { saveAsTemplate: boolean; templateName?: string }) =>
      api.post<ImportCommitResult>(`/api/plans/${planId}/import/sessions/${sessionId}/commit`, body),
  });
}

export function useDeleteImportSession(planId: string) {
  return useMutation({
    mutationFn: (sessionId: string) =>
      api.delete<void>(`/api/plans/${planId}/import/sessions/${sessionId}`),
  });
}
