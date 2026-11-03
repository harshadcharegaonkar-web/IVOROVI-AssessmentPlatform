import type { BadgeTone } from "@ivorovi/ui";

/** Client-side model of the student import (increment 1.6). The server re-validates everything. */

export type JobStatus = "UPLOADED" | "QUEUED" | "VALIDATING" | "READY" | "PROCESSING" | "COMPLETED" | "PARTIALLY_COMPLETED" | "FAILED" | "CANCELLED";
export type ImportMode = "ALL_OR_NOTHING" | "VALID_ROWS_ONLY";
export type Step = "upload" | "mapping" | "validation" | "duplicates" | "preview" | "confirm" | "progress" | "result";

export const STEPS: { step: Step; label: string }[] = [
  { step: "upload", label: "Upload" },
  { step: "mapping", label: "Map columns" },
  { step: "validation", label: "Validate" },
  { step: "duplicates", label: "Duplicates" },
  { step: "preview", label: "Preview" },
  { step: "confirm", label: "Confirm" },
  { step: "progress", label: "Import" },
  { step: "result", label: "Result" },
];

const STATUS: Record<JobStatus, { label: string; tone: BadgeTone }> = {
  UPLOADED: { label: "Mapping needed", tone: "info" },
  QUEUED: { label: "Queued", tone: "neutral" },
  VALIDATING: { label: "Validating", tone: "info" },
  READY: { label: "Ready to confirm", tone: "warning" },
  PROCESSING: { label: "Importing", tone: "info" },
  COMPLETED: { label: "Completed", tone: "success" },
  PARTIALLY_COMPLETED: { label: "Partially completed", tone: "warning" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
};

export function jobStatusText(s: string | undefined): string {
  return STATUS[s as JobStatus]?.label ?? (s ?? "");
}

export function jobStatusTone(s: string | undefined): BadgeTone {
  return STATUS[s as JobStatus]?.tone ?? "neutral";
}

/** Statuses during which the job page polls the server. */
export function isRunning(status: string | undefined): boolean {
  return status === "QUEUED" || status === "VALIDATING" || status === "PROCESSING";
}

export function isFinished(status: string | undefined): boolean {
  return status === "COMPLETED" || status === "PARTIALLY_COMPLETED" || status === "FAILED" || status === "CANCELLED";
}

/** The step a job naturally shows (where the administrator continues). */
export function currentStep(job: { status?: string; phase?: string; counts?: { unresolved?: number; potentialDuplicates?: number } }): Step {
  switch (job.status) {
    case "UPLOADED": return "mapping";
    case "QUEUED": return job.phase === "IMPORT" ? "progress" : "validation";
    case "VALIDATING": return "validation";
    case "READY": return (job.counts?.unresolved ?? 0) > 0 ? "duplicates" : "preview";
    case "PROCESSING": return "progress";
    case "FAILED": return job.phase === "IMPORT" ? "result" : "validation";
    default: return "result";
  }
}

/** Steps a job may open (earlier steps stay viewable; later ones need the job to get there). */
export function reachable(job: { status?: string; phase?: string }, step: Step): boolean {
  const s = job.status;
  const imported = job.phase === "IMPORT" && s !== "READY" && s !== "UPLOADED";
  switch (step) {
    case "upload": return false;
    case "mapping": return true;
    case "validation": return s !== "UPLOADED";
    case "duplicates":
    case "preview": return s === "READY" || imported;
    case "confirm": return s === "READY";
    case "progress": return imported;
    case "result": return imported || s === "CANCELLED";
  }
}

export const MODE_TEXT: Record<ImportMode, { title: string; description: string }> = {
  ALL_OR_NOTHING: {
    title: "All or nothing",
    description: "Every row must import. If any row fails, everything the import created is removed again and no record is kept. Needs a file without invalid or conflicting rows.",
  },
  VALID_ROWS_ONLY: {
    title: "Valid rows only",
    description: "Valid rows are imported; invalid, conflicting and skipped rows are reported with the reason so you can correct and upload them again.",
  },
};

export const MATCH_TEXT: Record<string, { label: string; tone: BadgeTone; help: string }> = {
  EXACT_DUPLICATE: { label: "Already exists", tone: "neutral", help: "The student and these records already exist. The row is skipped." },
  POTENTIAL_DUPLICATE: { label: "Possible duplicate", tone: "warning", help: "Another student has the same email, name and date of birth, or name and mobile." },
  CONFLICTING_IDENTITY: { label: "Conflicting identity", tone: "danger", help: "An identifier in the row belongs to a different student." },
  EXISTING_NEW_ENROLMENT: { label: "Existing student, new records", tone: "info", help: "The student exists; the row would add an admission, enrolment or guardians." },
  CONFLICTING_ACADEMIC: { label: "Conflicting academic records", tone: "danger", help: "The student exists with other academic records. Use the profile (transfers) instead." },
  IN_FILE_DUPLICATE: { label: "Repeated in file", tone: "warning", help: "An earlier row of the file has the same identifier." },
  IN_FILE_POTENTIAL: { label: "Possibly repeated in file", tone: "warning", help: "An earlier row may be the same person." },
};

export const ROW_STATUS_TEXT: Record<string, { label: string; tone: BadgeTone }> = {
  VALID: { label: "Valid", tone: "success" },
  WARNING: { label: "Valid with warnings", tone: "warning" },
  INVALID: { label: "Invalid", tone: "danger" },
  DUPLICATE: { label: "Duplicate", tone: "neutral" },
  NEEDS_DECISION: { label: "Needs decision", tone: "warning" },
  CONFLICT: { label: "Conflict", tone: "danger" },
};

export const OUTCOME_TEXT: Record<string, { label: string; tone: BadgeTone }> = {
  PENDING: { label: "Pending", tone: "neutral" },
  IMPORTED: { label: "Imported", tone: "success" },
  UPDATED: { label: "Records added", tone: "info" },
  SKIPPED: { label: "Skipped", tone: "neutral" },
  FAILED: { label: "Failed", tone: "danger" },
  ROLLED_BACK: { label: "Rolled back", tone: "warning" },
  NOT_PROCESSED: { label: "Not processed", tone: "neutral" },
};

export const DECISION_TEXT: Record<string, string> = {
  CREATE_NEW: "Create a new student",
  ADD_TO_EXISTING: "Add records to the existing student",
  SKIP: "Skip the row",
};

/** Mapping problems the administrator must fix before validation (mirrors the server's checks). */
export interface FieldInfo { key: string; label: string; section: string; required: boolean; personal: boolean }

export function mappingProblems(mapping: Record<number, string>, fields: FieldInfo[], personalAllowed: boolean): string[] {
  const used = Object.values(mapping).filter(Boolean);
  const problems: string[] = [];
  const counts = new Map<string, number>();
  used.forEach((f) => counts.set(f, (counts.get(f) ?? 0) + 1));
  const byKey = new Map(fields.map((f) => [f.key, f]));
  for (const [f, n] of counts) if (n > 1) problems.push(`${byKey.get(f)?.label ?? f} is mapped to ${n} columns.`);
  const missing = fields.filter((f) => f.required && !counts.has(f.key)).map((f) => f.label);
  const academic = used.some((f) => ["ADMISSION", "ENROLMENT"].includes(byKey.get(f)?.section ?? "") && f !== "institution" && f !== "department" && f !== "status");
  if (academic) {
    if (!counts.has("admission_no")) missing.push("Admission number");
    if (!counts.has("admission_date")) missing.push("Admission date");
    if (!counts.has("course") && !counts.has("batch")) missing.push("Course or Batch");
  }
  for (const n of [1, 2]) {
    if (used.some((f) => f.startsWith(`guardian${n}_`))) {
      if (!counts.has(`guardian${n}_name`)) missing.push(`Guardian ${n} name`);
      if (!counts.has(`guardian${n}_relationship`)) missing.push(`Guardian ${n} relationship`);
    }
  }
  if (missing.length) problems.push(`Map the required fields: ${missing.join(", ")}.`);
  if (!personalAllowed && used.some((f) => byKey.get(f)?.personal)) {
    problems.push("Personal and guardian columns need the 'View student personal details' permission.");
  }
  return problems;
}

export function percent(done: number | undefined, total: number | undefined): number {
  if (!total) return 0;
  return Math.min(100, Math.round(((done ?? 0) * 100) / total));
}

export function formatBytes(n: number | undefined): string {
  if (!n) return "0 B";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} kB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** Client-side pre-check of the chosen file (the server checks the content again). */
export function fileProblem(file: { name: string; size: number }, maxBytes: number): string | null {
  const name = file.name.toLowerCase();
  if (name.endsWith(".xlsm") || name.endsWith(".xlsb") || name.endsWith(".xls")) return "Macro-enabled and legacy workbooks are not accepted. Save the file as .xlsx or .csv.";
  if (!name.endsWith(".xlsx") && !name.endsWith(".csv")) return "Choose an Excel workbook (.xlsx) or a CSV file (.csv).";
  if (file.size === 0) return "The file is empty.";
  if (file.size > maxBytes) return `The file is larger than ${formatBytes(maxBytes)}.`;
  return null;
}
