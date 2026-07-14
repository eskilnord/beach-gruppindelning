import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { api, ApiError, resetBackendInfoCacheForTests, restartBackend } from "./client";
import * as platform from "../lib/platform";

describe("apiFetch", () => {
  it("attaches the X-GP-Token header resolved from platform.ts (browser dev token)", async () => {
    let receivedToken: string | null = null;
    server.use(
      http.get("/api/probe", ({ request }) => {
        receivedToken = request.headers.get("X-GP-Token");
        return HttpResponse.json({ ok: true });
      }),
    );

    await api.get("/api/probe");

    expect(receivedToken).toBe("dev");
  });

  it("normalizes the backend's uniform {error} JSON body into ApiError", async () => {
    server.use(
      http.get("/api/broken", () =>
        HttpResponse.json({ error: "Season plan not found: x" }, { status: 404 }),
      ),
    );

    await expect(api.get("/api/broken")).rejects.toMatchObject({
      name: "ApiError",
      message: "Season plan not found: x",
      status: 404,
    });
  });

  it("falls back to a generic message when the error response body isn't JSON", async () => {
    server.use(http.get("/api/broken-text", () => new HttpResponse("oops", { status: 500 })));

    const error = await api.get("/api/broken-text").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect((error as ApiError).message).toContain("500");
  });

  it("serializes JSON bodies and sets Content-Type on POST", async () => {
    let receivedBody: unknown = null;
    let receivedContentType: string | null = null;
    server.use(
      http.post("/api/echo", async ({ request }) => {
        receivedContentType = request.headers.get("Content-Type");
        receivedBody = await request.json();
        return HttpResponse.json({ ok: true }, { status: 201 });
      }),
    );

    await api.post("/api/echo", { name: "VT27" });

    expect(receivedContentType).toContain("application/json");
    expect(receivedBody).toEqual({ name: "VT27" });
  });

  it("returns undefined for 204 No Content responses", async () => {
    server.use(http.delete("/api/thing/1", () => new HttpResponse(null, { status: 204 })));

    await expect(api.delete("/api/thing/1")).resolves.toBeUndefined();
  });
});

describe("backend-info cache recovery", () => {
  beforeEach(() => {
    resetBackendInfoCacheForTests();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetBackendInfoCacheForTests();
  });

  it("clears the cache on a rejected backend-info load so the next apiFetch call retries instead of replaying the failure forever", async () => {
    const getBackendInfoSpy = vi
      .spyOn(platform, "getBackendInfo")
      .mockRejectedValueOnce(new Error("handshake failed"))
      .mockResolvedValueOnce({ base_url: "", token: "dev" });

    server.use(http.get("/api/probe", () => HttpResponse.json({ ok: true })));

    await expect(api.get("/api/probe")).rejects.toThrow("handshake failed");
    await expect(api.get("/api/probe")).resolves.toEqual({ ok: true });
    expect(getBackendInfoSpy).toHaveBeenCalledTimes(2);
  });

  it("restartBackend() replaces the cache with the freshly resolved backend info", async () => {
    const restartSpy = vi
      .spyOn(platform, "restartBackend")
      .mockResolvedValue({ base_url: "", token: "restarted-token" });

    let receivedToken: string | null = null;
    server.use(
      http.get("/api/probe", ({ request }) => {
        receivedToken = request.headers.get("X-GP-Token");
        return HttpResponse.json({ ok: true });
      }),
    );

    await restartBackend();
    await api.get("/api/probe");

    expect(restartSpy).toHaveBeenCalledTimes(1);
    expect(receivedToken).toBe("restarted-token");
  });

  it("restartBackend() clears the cache and rethrows when the respawn itself fails", async () => {
    vi.spyOn(platform, "restartBackend").mockRejectedValueOnce(new Error("respawn failed"));

    await expect(restartBackend()).rejects.toThrow("respawn failed");

    // The cache must not be left pointing at that rejection - the next call should re-resolve
    // backend info (browser dev fallback here) rather than replay the same rejected promise.
    server.use(http.get("/api/probe", () => HttpResponse.json({ ok: true })));
    await expect(api.get("/api/probe")).resolves.toEqual({ ok: true });
  });
});
