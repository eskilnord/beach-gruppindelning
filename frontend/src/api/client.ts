import { getBackendInfo, restartBackend as restartBackendInfo, type BackendInfo } from "../lib/platform";

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
    const promise = getBackendInfo();
    backendInfoPromise = promise;
    // A crashed-before-first-request backend (or one whose Tauri handshake never completes) leaves
    // a permanently rejected promise cached, so every subsequent call would fail forever even after
    // the backend recovers. Clear the cache once it rejects — but only if nothing else has already
    // replaced it (e.g. a concurrent restartBackend() call) — so the next apiFetch re-resolves it.
    // Attaching this via a separate `.catch` (rather than awaiting here) means callers of
    // `loadBackendInfo()` still see the original rejection on the promise we return below.
    promise.catch(() => {
      if (backendInfoPromise === promise) {
        backendInfoPromise = null;
      }
    });
  }
  return backendInfoPromise;
}

/** Test-only escape hatch to force re-resolution of backend info between test cases. */
export function resetBackendInfoCacheForTests(): void {
  backendInfoPromise = null;
}

/**
 * Recovers from a mid-session backend crash (verified problem: previously required restarting the
 * whole packaged app) by delegating to platform.ts's `restartBackend` (Tauri: kills+respawns the
 * backend child and re-handshakes via the `retry_backend` command; browser: no-op fixed dev info)
 * and atomically swapping the cache to the fresh result so the very next `apiFetch`/`apiUpload`/
 * `apiDownload` call talks to the new port/token instead of replaying a stale or rejected promise.
 * On failure the cache is cleared (not left pointing at a rejected promise) and the error is
 * rethrown so the caller (ReconnectOverlay) can show it.
 */
export async function restartBackend(): Promise<BackendInfo> {
  const cachedBeforeRestart = backendInfoPromise;
  try {
    const info = await restartBackendInfo();
    backendInfoPromise = Promise.resolve(info);
    return info;
  } catch (error) {
    // Only clear what we started with: a concurrent loadBackendInfo() may have re-resolved fresh
    // (correct) info while the restart was in flight — don't discard it on our failure.
    if (backendInfoPromise === cachedBeforeRestart) {
      backendInfoPromise = null;
    }
    throw error;
  }
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

/** Extracts the filename from a `Content-Disposition: attachment; filename="..."` header (RFC 6266),
 *  falling back to the RFC 5987 `filename*=UTF-8''...` form some servers prefer for non-ASCII names,
 *  then to `fallback` if the header is missing or unparseable. */
function filenameFromContentDisposition(header: string | null, fallback: string): string {
  if (!header) {
    return fallback;
  }
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (extended) {
    try {
      return decodeURIComponent(extended[1]);
    } catch {
      // Malformed percent-encoding - fall through to the other patterns/fallback.
    }
  }
  const quoted = /filename="([^"]*)"/i.exec(header);
  if (quoted) {
    return quoted[1];
  }
  const bare = /filename=([^;]+)/i.exec(header);
  return bare ? bare[1].trim() : fallback;
}

export interface DownloadResult {
  blob: Blob;
  filename: string;
}

/**
 * Binary-response variant of {@link apiFetch} for the M8 export endpoints (spec §20/§21.3), which
 * return `ResponseEntity<byte[]>` rather than JSON - `toResult`'s `JSON.parse` would throw on the raw
 * bytes, hence this separate primitive (same precedent as `apiUpload` existing alongside `apiFetch`
 * for the request side). The backend sets `Content-Disposition` with the real export filename
 * (`ExportController`); callers hand the result straight to `platform.ts#saveFile`.
 */
export async function apiDownload(path: string, fallbackFilename = "export"): Promise<DownloadResult> {
  const { base_url, token } = await loadBackendInfo();
  const headers = new Headers();
  headers.set("X-GP-Token", token);

  const response = await fetch(`${base_url}${path}`, { headers });
  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorMessage(response));
  }

  const blob = await response.blob();
  const filename = filenameFromContentDisposition(response.headers.get("Content-Disposition"), fallbackFilename);
  return { blob, filename };
}

export const api = {
  get: <T>(path: string) => apiFetch<T>(path),
  post: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "POST", body }),
  put: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "PUT", body }),
  patch: <T>(path: string, body?: unknown) => apiFetch<T>(path, { method: "PATCH", body }),
  delete: <T>(path: string) => apiFetch<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, formData: FormData) => apiUpload<T>(path, formData),
  download: (path: string, fallbackFilename?: string) => apiDownload(path, fallbackFilename),
};
