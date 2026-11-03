import { describe, expect, it } from "vitest";
import { currentStep, fileProblem, isRunning, mappingProblems, percent, reachable } from "./importModel";

const FIELDS = [
  { key: "first_name", label: "First name", section: "PERSONAL", required: true, personal: false },
  { key: "student_no", label: "Student ID", section: "IDENTIFICATION", required: true, personal: false },
  { key: "mobile", label: "Mobile", section: "PERSONAL", required: false, personal: true },
  { key: "admission_no", label: "Admission number", section: "ADMISSION", required: false, personal: false },
  { key: "admission_date", label: "Admission date", section: "ADMISSION", required: false, personal: false },
  { key: "batch", label: "Batch", section: "ENROLMENT", required: false, personal: false },
  { key: "guardian1_name", label: "Guardian 1 name", section: "GUARDIAN", required: false, personal: true },
  { key: "guardian1_relationship", label: "Guardian 1 relationship", section: "GUARDIAN", required: false, personal: true },
];

describe("import model", () => {
  it("routes a job to the step where it continues", () => {
    expect(currentStep({ status: "UPLOADED" })).toBe("mapping");
    expect(currentStep({ status: "VALIDATING" })).toBe("validation");
    expect(currentStep({ status: "READY", counts: { unresolved: 2 } })).toBe("duplicates");
    expect(currentStep({ status: "READY", counts: { unresolved: 0 } })).toBe("preview");
    expect(currentStep({ status: "QUEUED", phase: "IMPORT" })).toBe("progress");
    expect(currentStep({ status: "FAILED", phase: "VALIDATE" })).toBe("validation");
    expect(currentStep({ status: "PARTIALLY_COMPLETED", phase: "IMPORT" })).toBe("result");
  });

  it("only allows confirmation of validated jobs", () => {
    expect(reachable({ status: "READY" }, "confirm")).toBe(true);
    expect(reachable({ status: "PROCESSING", phase: "IMPORT" }, "confirm")).toBe(false);
    expect(reachable({ status: "UPLOADED" }, "validation")).toBe(false);
    expect(isRunning("PROCESSING")).toBe(true);
    expect(isRunning("COMPLETED")).toBe(false);
  });

  it("reports mapping problems like the server", () => {
    expect(mappingProblems({ 0: "first_name", 1: "student_no" }, FIELDS, true)).toEqual([]);
    expect(mappingProblems({ 0: "first_name", 1: "first_name", 2: "student_no" }, FIELDS, true)[0]).toContain("mapped to 2 columns");
    expect(mappingProblems({ 0: "first_name" }, FIELDS, true)[0]).toContain("Student ID");
    expect(mappingProblems({ 0: "first_name", 1: "student_no", 2: "batch" }, FIELDS, true)[0]).toContain("Admission number");
    expect(mappingProblems({ 0: "first_name", 1: "student_no", 2: "guardian1_name" }, FIELDS, true)[0]).toContain("Guardian 1 relationship");
    expect(mappingProblems({ 0: "first_name", 1: "student_no", 2: "mobile" }, FIELDS, false)[0]).toContain("personal details");
  });

  it("checks files before upload and computes progress", () => {
    expect(fileProblem({ name: "a.xlsm", size: 10 }, 100)).toContain("Macro");
    expect(fileProblem({ name: "a.pdf", size: 10 }, 100)).toContain(".xlsx");
    expect(fileProblem({ name: "a.csv", size: 200 }, 100)).toContain("larger");
    expect(fileProblem({ name: "Students.XLSX", size: 10 }, 100)).toBeNull();
    expect(percent(25, 100)).toBe(25);
    expect(percent(5, 0)).toBe(0);
  });
});
