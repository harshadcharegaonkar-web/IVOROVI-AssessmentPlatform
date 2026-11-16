import { describe, expect, it } from "vitest";
import { parseLimit } from "./SecurityPolicyPage";

describe("parseLimit", () => {
  it("treats empty as no limit and validates whole numbers in range", () => {
    expect(parseLimit("", 5, 1440)).toEqual({ value: null });
    expect(parseLimit("  ", 5, 1440)).toEqual({ value: null });
    expect(parseLimit("30", 5, 1440)).toEqual({ value: 30 });
    expect(parseLimit(" 1440 ", 5, 1440)).toEqual({ value: 1440 });
    expect(parseLimit("4", 5, 1440).error).toBe("Between 5 and 1440");
    expect(parseLimit("1441", 5, 1440).error).toBe("Between 5 and 1440");
    expect(parseLimit("2.5", 1, 720).error).toBe("Whole number");
    expect(parseLimit("-3", 1, 720).error).toBe("Whole number");
    expect(parseLimit("ten", 1, 720).error).toBe("Whole number");
  });
});
