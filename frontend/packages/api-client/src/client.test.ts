import { describe, expect, it, vi } from "vitest";
import { createApiClient, unwrap } from "./client";
import { ApiError } from "./errors";

function json(status: number, body: unknown, type = "application/json") {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": type } });
}

describe("api client", () => {
  it("adds the bearer token", async () => {
    const fetchMock = vi.fn(async (req: Request) => {
      expect(req.headers.get("Authorization")).toBe("Bearer t0k");
      return json(200, { userId: "u" });
    });
    const api = createApiClient({ baseUrl: "http://api", getAccessToken: () => "t0k", fetch: fetchMock as unknown as typeof fetch });
    const me = unwrap(await api.GET("/api/v1/me"));
    expect(me.userId).toBe("u");
  });

  it("maps problem+json to ApiError and signals 401", async () => {
    const onUnauthorized = vi.fn();
    const api = createApiClient({
      baseUrl: "http://api", getAccessToken: () => undefined, onUnauthorized,
      fetch: (async () => json(401, { code: "unauthenticated", detail: "A valid access token is required.", traceId: "tr1" }, "application/problem+json")) as unknown as typeof fetch,
    });
    await expect(api.GET("/api/v1/me")).rejects.toMatchObject({ status: 401, code: "unauthenticated", traceId: "tr1" });
    expect(onUnauthorized).toHaveBeenCalled();
  });

  it("maps network failures", async () => {
    const api = createApiClient({ baseUrl: "http://api", getAccessToken: () => undefined,
      fetch: (async () => { throw new TypeError("Failed to fetch"); }) as unknown as typeof fetch });
    const err = await api.GET("/api/v1/me").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe("network_error");
  });
});
