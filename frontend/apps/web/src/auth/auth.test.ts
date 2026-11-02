import { describe, expect, it } from "vitest";
import { safeReturnPath } from "./auth";

describe("safeReturnPath", () => {
  it.each([
    ["/organization/colleges?q=a", "/organization/colleges?q=a"],
    ["https://evil.example/x", "/"],
    ["//evil.example", "/"],
    ["/auth/callback?code=1", "/"],
    [undefined, "/"],
    [42, "/"],
  ])("%s -> %s", (input, expected) => {
    expect(safeReturnPath(input)).toBe(expected);
  });
});
