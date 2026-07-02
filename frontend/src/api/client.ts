import { getBackendInfo, type BackendInfo } from "../lib/platform";

/**
 * Thrown for any non-2xx API response. `message` is the normalized human-readable text: the
 * backend's uniform `{"error": "..."}` shape (backend/docs/m1-notes.md "Error shape") when present,
 * otherwise a generic fallback derived from the HTTP status.
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** True for a 404 ApiError — used by the import wizard to detect an expired/unknown ImportSession
 *  (backend/docs/m3-notes.md: sessions purge after 1h of inactivity) and show the Swedish
 *  "Sessionen har gått ut" screen instead of a generic error. */
export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

// getBackendInfo() resolves synchronously-fast in browser dev mode and via a Tauri `invoke` call in
// the desktop shell; caching the promise means every request awaits the same resolved value instead
// of re-invoking the Tauri command per request.
let backendInfoPromise: Promise<BackendInfo> | null = null;

function loadBackendInfo(): Promise<BackendInfo> {
  if (!backendInfoPromise) {
    backendInfoPromise = getBackendInfo();
  }
  return backendInfoPromise;
}

/** Test-only escape hatch to force re-resolution of backend info between test cases. */
export function resetBackendInfoCacheForTests(): void {
  backendInfoPromise = null;
}

async function parseErrorMessage(response: Response): Promise<string> {
  try {
    const body: unknown = await response.clone().json();
    if (body && typeof body === "object" && "error" in body && typeof (body as { error: unknown }).error === "string") {
      return (body as { error: string }).error;
    }
  } catch {
    // Response body wasn't JSON (or was empty) — fall through to the generic message.
  }
  return `Request failed with status ${response.status}`;
}

export interface ApiFetchOptions extends Omit<RequestInit, "body"> {
  body?: unknown;
}

async function toResult<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorMessage(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  return (text.length === 0 ? undefined : JSON.parse(text)) as T;
}

/**
 * Fetch wrapper used by every API call in the app: resolves the backend base URL + token via
 * platform.ts, attaches the X-GP-Token header (docs/design/01-architecture.md §4), serializes JSON
 * bodies, and normalizes error responses into ApiError.
 */
export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { base_url, token } = await loadBackendInfo();
  const { body, headers: initHeaders, ...rest } = options;

  const headers = new Headers(initHeaders);
  headers.set("X-GP-Token", token);
  const hasBody = body !== undefined;
  if (hasBody && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${base_url}${path}`, {
    ...rest,
    headers,
    body: hasBody ? JSON.stringify(body) : undefined,
  });

  return toResult<T>(response);
}

/**
 * Multipart upload variant for the import wizard's file-upload step (spec §8.3 step 1). Deliberately
 * does not set Content-Type: the browser must generate the multipart boundary itself from the
 * FormData body, same as apiFetch otherwise (token header, ApiError normalization).
 */
export async function apiUpload<T>(path: string, formData: FormData): Promise<T> {
  const { base_url, token } = await loadBackendInfo();
  const headers = new Headers();
  headers.set("X-GP-Token", token);

  const response = await fetch(`${base_url}${path}`, {
    method: "POST",
    headers,
    body: formData,
  });

  return toResult<T>(response);
}

export const api = {
  get: <T>(path: string) => apiFetch<T>(path),
  post: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "POST", body }),
  put: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "PUT", body }),
  patch: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "PATCH", body }),
  delete: <T>(path: string) => apiFetch<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, formData: FormData) => apiUpload<T>(path, formData),
};
