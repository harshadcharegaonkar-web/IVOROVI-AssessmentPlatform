import { describe, expect, it } from "vitest";
import { safeReturnPath } from "./auth";
import { parseConfig, portalKeyFromPath } from "./config";
import { examCsp, examHeaders } from "../security-headers";

describe("exam app configuration and boundaries", () => {
  it("resolves the portal key only from the path", () => {
    expect(portalKeyFromPath("/p/meridian-entrance")).toBe("meridian-entrance");
    expect(portalKeyFromPath("/p/meridian-entrance/home")).toBe("meridian-entrance");
    expect(portalKeyFromPath("/p/AB")).toBeNull();
    expect(portalKeyFromPath("/students")).toBeNull();
  });

  it.each([
    ["/p/meridian", "/p/meridian"],
    ["https://evil.example/p/x", "/"],
    ["//evil.example", "/"],
    ["/students", "/"],
    [undefined, "/"],
  ])("return path %s -> %s", (input, expected) => {
    expect(safeReturnPath(input)).toBe(expected);
  });

  it("rejects invalid configuration", () => {
    expect(() => parseConfig({ oidcAuthority: "javascript:alert(1)", oidcClientId: "x" })).toThrow();
    expect(parseConfig({ oidcAuthority: "https://idp/realms/c/", oidcClientId: "ivorovi-exam" }).oidcAuthority).toBe("https://idp/realms/c");
  });

  it("has a strict CSP without inline code and a closed permissions policy", () => {
    const csp = examCsp("https://idp/realms/ivorovi-candidates");
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("style-src 'self'");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("unsafe-eval");
    expect(csp).toContain("frame-ancestors 'none'");
    const h = examHeaders("https://idp/realms/ivorovi-candidates");
    expect(h["Referrer-Policy"]).toBe("no-referrer");
    expect(h["Permissions-Policy"]).toContain("camera=(self)");
    expect(h["Permissions-Policy"]).toContain("microphone=()");
  });
});
