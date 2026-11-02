import { describe, expect, it } from "vitest";
import { auditFilterSchema, toQuery } from "./auditFilter";

describe("audit filter", () => {
  it("rejects invalid action patterns and inverted ranges", () => {
    expect(auditFilterSchema.safeParse({ action: "DROP TABLE", outcome: "", from: "", to: "" }).success).toBe(false);
    const r = auditFilterSchema.safeParse({ action: "", outcome: "", from: "2026-11-02T10:00", to: "2026-11-01T10:00" });
    expect(r.success).toBe(false);
  });

  it("converts to API query", () => {
    const q = toQuery({ action: "feature.override.changed", outcome: "DENIED", from: "", to: "" });
    expect(q).toEqual({ action: "feature.override.changed", outcome: "DENIED", from: undefined, to: undefined });
  });
});
