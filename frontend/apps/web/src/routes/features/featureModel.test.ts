import { describe, expect, it } from "vitest";
import { describeSource, groupByCategory, nextActionFor } from "./featureModel";

describe("feature model", () => {
  it("requires confirmation only when enabling AI", () => {
    expect(nextActionFor({ category: "AI" }, true)).toBe("confirm");
    expect(nextActionFor({ category: "AI" }, false)).toBe("apply");
    expect(nextActionFor({ category: "MODULE" }, true)).toBe("apply");
  });

  it("groups flags by category in a stable order", () => {
    const groups = groupByCategory([{ key: "ai.x", category: "AI" }, { key: "module.y", category: "MODULE" }]);
    expect(groups.map((g) => g.title)).toEqual(["Modules", "AI capabilities"]);
  });

  it("explains where a value comes from", () => {
    expect(describeSource({ source: "DEFAULT", defaultEnabled: false })).toBe("Default (off)");
    expect(describeSource({ source: "DEPLOYMENT" })).toBe("Disabled for this deployment");
  });
});
