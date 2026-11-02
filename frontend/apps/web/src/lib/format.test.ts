import { describe, expect, it } from "vitest";
import { formatNumber, formatRelative } from "./format";

describe("format", () => {
  const now = new Date("2026-11-02T12:00:00Z");
  it("formats relative times", () => {
    expect(formatRelative("2026-11-02T11:59:30Z", now)).toBe("just now");
    expect(formatRelative("2026-11-02T11:15:00Z", now)).toBe("45 min ago");
    expect(formatRelative("2026-11-02T07:00:00Z", now)).toBe("5 h ago");
    expect(formatRelative("2026-10-30T12:00:00Z", now)).toBe("3 d ago");
    expect(formatRelative(undefined, now)).toBe("");
  });
  it("formats numbers with Indian grouping", () => {
    expect(formatNumber(1234567)).toBe("12,34,567");
    expect(formatNumber(undefined)).toBe("—");
  });
});
