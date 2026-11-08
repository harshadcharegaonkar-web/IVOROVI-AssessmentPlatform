import { expect, test } from "@playwright/test";
import { DELIVERY } from "./support";

const ORIGIN = process.env.E2E_HEADERS_URL ?? process.env.E2E_BASE_URL ?? "http://localhost:5174";

test.describe("exam application browser and API boundary (C-4)", () => {
  test("strict security headers on the served application", async ({ request }) => {
    const r = await request.get(`${ORIGIN}/p/any-portal`);
    expect(r.status()).toBe(200);
    const h = r.headers();
    const csp = h["content-security-policy"] ?? "";
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("style-src 'self'");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("unsafe-eval");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toMatch(/connect-src 'self' https?:\/\/[^ ]+\/realms\/ivorovi-candidates\//);
    expect(h["referrer-policy"]).toBe("no-referrer");
    expect(h["permissions-policy"]).toContain("camera=(self)");
    expect(h["permissions-policy"]).toContain("microphone=()");
    expect(h["cross-origin-opener-policy"]).toBe("same-origin");
    expect(h["cross-origin-embedder-policy"]).toBe("require-corp");
    expect(h["x-content-type-options"]).toBe("nosniff");
    expect(h["x-frame-options"]).toBe("DENY");
  });

  test("the delivery process serves no staff endpoint", async ({ request }) => {
    for (const p of ["/api/v1/me", "/api/v1/students", "/api/v1/approvals", "/api/v1/exam/portals", "/api/v1/users", "/v3/api-docs"]) {
      expect((await request.get(`${DELIVERY}${p}`)).status(), p).toBe(404);
    }
    expect((await request.get(`${DELIVERY}/api/v1/delivery/time`)).status()).toBe(200);
  });
});
