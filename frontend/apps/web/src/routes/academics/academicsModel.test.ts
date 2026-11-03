import { describe, expect, it } from "vitest";
import { calendarTransitions, defaultGraduationYear, defaultTotalTerms, formatDate, groupByTerm, offeringTransitions, rangesOverlap, statusTone, termsPerYear, treeNodes } from "./academicsModel";

describe("academic model", () => {
  it("derives terms like the server (no fixed two-semester assumption)", () => {
    expect(termsPerYear("SEMESTER")).toBe(2);
    expect(termsPerYear("TRIMESTER")).toBe(3);
    expect(termsPerYear("QUARTER")).toBe(4);
    expect(termsPerYear("ANNUAL")).toBe(1);
    expect(termsPerYear("OTHER", 5)).toBe(5);
    expect(termsPerYear("OTHER")).toBeNull();
    expect(defaultTotalTerms("SEMESTER", 4)).toBe(8);
    expect(defaultTotalTerms("TRIMESTER", 2)).toBe(6);
    expect(defaultTotalTerms("SEMESTER", 1.5)).toBe(3);
    expect(defaultTotalTerms("QUARTER", 1.3)).toBe(6);          // 5.2 rounds up
    expect(defaultTotalTerms("SEMESTER", 0)).toBeNull();
  });

  it("computes graduation years and date overlaps", () => {
    expect(defaultGraduationYear(2026, 4)).toBe(2030);
    expect(defaultGraduationYear(2026, 1.5)).toBe(2028);
    expect(defaultGraduationYear(2026, undefined)).toBeNull();
    expect(rangesOverlap("2026-07-01", "2026-12-31", "2026-12-31", "2027-03-01")).toBe(true);
    expect(rangesOverlap("2026-07-01", "2026-12-30", "2026-12-31", "2027-03-01")).toBe(false);
  });

  it("formats date-only values without shifting the day", () => {
    expect(formatDate("2026-07-01")).toMatch(/2026/);
    expect(formatDate("2026-07-01")).toMatch(/1/);
    expect(formatDate(undefined)).toBe("—");
  });

  it("groups a curriculum by term including empty terms", () => {
    const groups = groupByTerm([{ termNumber: 1 }, { termNumber: 3 }, { termNumber: 1 }], 4);
    expect(groups.map((g) => g.entries.length)).toEqual([2, 0, 1, 0]);
    expect(groupByTerm([{ termNumber: 6 }], 4)).toHaveLength(6);
  });

  it("mirrors server status transitions", () => {
    expect(offeringTransitions("PLANNED")).toEqual(["ACTIVE", "CANCELLED"]);
    expect(offeringTransitions("COMPLETED")).toEqual([]);
    expect(calendarTransitions("ACTIVE")).toEqual([{ to: "COMPLETED", correction: false }, { to: "PLANNED", correction: true }]);
    expect(calendarTransitions("COMPLETED")).toEqual([{ to: "ACTIVE", correction: true }]);
    expect(statusTone("CANCELLED")).toBe("danger");
  });

  it("flattens the structure tree with parent links", () => {
    const nodes = treeNodes({ truncated: false, programs: [{ id: "p", code: "BT", name: "B.Tech", status: "ACTIVE", ownerName: "AIT", totalTerms: 8,
      courses: [{ id: "c", code: "CSE", name: "CSE", status: "ACTIVE", departmentName: "CS", subjects: 3, batches: [{ id: "b", code: "B1", name: "2026", admissionYear: 2026, status: "ACTIVE", offerings: 2 }] }] }] });
    expect(nodes.map((n) => [n.id, n.parentId, n.kind])).toEqual([["p:p", null, "program"], ["c:c", "p:p", "course"], ["b:b", "c:c", "batch"]]);
    expect(nodes[2]!.to).toBe("/academics/batches/b");
  });
});
