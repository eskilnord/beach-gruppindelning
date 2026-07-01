import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { api, ApiError } from "./client";

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
