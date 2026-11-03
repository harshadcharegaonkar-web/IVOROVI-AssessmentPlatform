import { describe, expect, it } from "vitest";
import { availableChanges, fileSize, manualTransitions, needsReason, renderTemplate, statusText, statusTone, transitionVerb, uploadProblem } from "./studentModel";

describe("student model", () => {
  it("mirrors the server lifecycle; admitted and enrolled come only from records", () => {
    expect(manualTransitions("PROSPECTIVE")).toEqual(["CANCELLED"]);
    expect(manualTransitions("ADMITTED")).toEqual(["WITHDRAWN", "CANCELLED"]);
    expect(manualTransitions("ENROLLED")).toEqual(["ACTIVE", "SUSPENDED", "WITHDRAWN"]);
    expect(manualTransitions("SUSPENDED")).toEqual(["ACTIVE", "WITHDRAWN"]);
    expect(manualTransitions("WITHDRAWN")).toEqual([]);
    expect(manualTransitions("GRADUATED")).toEqual(["ALUMNI"]);
    expect(transitionVerb("SUSPENDED", "ACTIVE")).toBe("Reinstate");
    expect(statusText("ALUMNI")).toBe("Alumni");
    expect(statusTone("SUSPENDED")).toBe("warning");
  });

  it("offers enrolment changes by state", () => {
    expect(availableChanges("ADMITTED", false)).toEqual(["INITIAL"]);
    expect(availableChanges("ACTIVE", true)).toContain("COURSE_TRANSFER");
    expect(availableChanges("SUSPENDED", true)).toEqual([]);
    expect(availableChanges("PROSPECTIVE", false)).toEqual([]);
    expect(needsReason("BATCH_TRANSFER")).toBe(true);
    expect(needsReason("PROGRESSION")).toBe(false);
  });

  it("pre-checks uploads (the server decides from the content)", () => {
    expect(uploadProblem({ size: 0, type: "application/pdf" }, "OTHER")).toMatch(/empty/);
    expect(uploadProblem({ size: 6 * 1024 * 1024, type: "application/pdf" }, "OTHER")).toMatch(/5 MB/);
    expect(uploadProblem({ size: 10, type: "application/x-msdownload" }, "OTHER")).toMatch(/PDF, JPEG or PNG/);
    expect(uploadProblem({ size: 10, type: "application/pdf" }, "PHOTOGRAPH")).toMatch(/JPEG or PNG/);
    expect(uploadProblem({ size: 10, type: "image/png" }, "PHOTOGRAPH")).toBeNull();
    expect(fileSize(2048)).toBe("2 KB");
  });

  it("renders numbering templates like the server", () => {
    expect(renderTemplate("{INST}{YYYY}{SEQ:4}", "AIT", 2026, 7)).toBe("AIT20260007");
    expect(renderTemplate("{INST}/{YY}/{SEQ:3}", "m-ie", 2026, 12)).toBe("MIE/26/012");
  });
});
