import { describe, expect, it } from "vitest";
import { parentOptions } from "./orgModel";

const tree = [
  { id: "u", type: "UNIVERSITY" as const, parentId: null },
  { id: "c", type: "COLLEGE" as const, parentId: "u" },
  { id: "k", type: "CAMPUS" as const, parentId: "c" },
  { id: "d", type: "DEPARTMENT" as const, parentId: "k" },
  { id: "x", type: "COLLEGE" as const, parentId: null, status: "INACTIVE" },
];

describe("parentOptions", () => {
  it("offers only allowed, active parent types", () => {
    expect(parentOptions("COLLEGE", tree).map((t) => t.id)).toEqual(["u"]);
    expect(parentOptions("CAMPUS", tree).map((t) => t.id)).toEqual(["u", "c"]);
    expect(parentOptions("DEPARTMENT", tree).map((t) => t.id)).toEqual(["u", "c", "k"]);
    expect(parentOptions("UNIVERSITY", tree)).toEqual([]);
  });

  it("never offers the moving unit or its descendants", () => {
    expect(parentOptions("DEPARTMENT", tree, "c").map((t) => t.id)).toEqual(["u"]);
  });
});
