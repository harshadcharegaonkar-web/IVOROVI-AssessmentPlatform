import { describe, expect, it } from "vitest";
import { parseConfig } from "./config";

describe("parseConfig", () => {
  it("accepts a valid configuration", () => {
    expect(parseConfig({ oidcAuthority: "http://localhost:8180/realms/ivorovi", oidcClientId: "ivorovi-web", apiBaseUrl: "" }))
      .toEqual({ oidcAuthority: "http://localhost:8180/realms/ivorovi", oidcClientId: "ivorovi-web", apiBaseUrl: "" });
  });
  it("rejects invalid values", () => {
    expect(() => parseConfig({ oidcAuthority: "javascript:alert(1)", oidcClientId: "x", apiBaseUrl: "" })).toThrow();
    expect(() => parseConfig({ oidcAuthority: "https://idp", oidcClientId: "", apiBaseUrl: "" })).toThrow();
    expect(() => parseConfig(null)).toThrow();
  });
});
