import type { BadgeTone } from "@ivorovi/ui";

/** Pure helpers of the student screens (no React); unit-tested in studentModel.test.ts. */

export type StudentStatus = "PROSPECTIVE" | "ADMITTED" | "ENROLLED" | "ACTIVE" | "SUSPENDED" | "WITHDRAWN" | "GRADUATED" | "ALUMNI" | "CANCELLED";
export type EnrolmentChange = "INITIAL" | "PROGRESSION" | "PROMOTION" | "BATCH_TRANSFER" | "COURSE_TRANSFER";

export const STATUSES: StudentStatus[] = ["PROSPECTIVE", "ADMITTED", "ENROLLED", "ACTIVE", "SUSPENDED", "WITHDRAWN", "GRADUATED", "ALUMNI", "CANCELLED"];

/** Lifecycle model (mirrors the server; the server enforces it). ADMITTED and ENROLLED come from records. */
export const TRANSITIONS: Record<StudentStatus, StudentStatus[]> = {
  PROSPECTIVE: ["ADMITTED", "CANCELLED"],
  ADMITTED: ["ENROLLED", "WITHDRAWN", "CANCELLED"],
  ENROLLED: ["ACTIVE", "SUSPENDED", "WITHDRAWN"],
  ACTIVE: ["SUSPENDED", "WITHDRAWN", "GRADUATED"],
  SUSPENDED: ["ACTIVE", "WITHDRAWN"],
  GRADUATED: ["ALUMNI"],
  WITHDRAWN: [],
  CANCELLED: [],
  ALUMNI: [],
};

export function manualTransitions(status: StudentStatus | undefined): StudentStatus[] {
  return status ? TRANSITIONS[status].filter((s) => s !== "ADMITTED" && s !== "ENROLLED") : [];
}

const LABELS: Record<StudentStatus, string> = {
  PROSPECTIVE: "Prospective", ADMITTED: "Admitted", ENROLLED: "Enrolled", ACTIVE: "Active", SUSPENDED: "Suspended",
  WITHDRAWN: "Withdrawn", GRADUATED: "Graduated", ALUMNI: "Alumni", CANCELLED: "Cancelled",
};
export function statusText(s: string | undefined): string {
  return (s ? LABELS[s as StudentStatus] : undefined) ?? (s ?? "—");
}

const TONES: Record<StudentStatus, BadgeTone> = {
  PROSPECTIVE: "neutral", ADMITTED: "info", ENROLLED: "info", ACTIVE: "success", SUSPENDED: "warning",
  WITHDRAWN: "danger", GRADUATED: "success", ALUMNI: "neutral", CANCELLED: "danger",
};
export function statusTone(s: string | undefined): BadgeTone {
  return (s ? TONES[s as StudentStatus] : undefined) ?? "neutral";
}

/** Verb for a status action button. */
export function transitionVerb(from: StudentStatus | undefined, to: StudentStatus): string {
  switch (to) {
    case "ACTIVE": return from === "SUSPENDED" ? "Reinstate" : "Mark active";
    case "SUSPENDED": return "Suspend";
    case "WITHDRAWN": return "Withdraw";
    case "GRADUATED": return "Mark graduated";
    case "ALUMNI": return "Move to alumni";
    case "CANCELLED": return "Cancel";
    default: return statusText(to);
  }
}

export const CHANGE_LABELS: Record<EnrolmentChange, string> = {
  INITIAL: "Initial enrolment", PROGRESSION: "Next term (progression)", PROMOTION: "Next academic year (promotion)",
  BATCH_TRANSFER: "Batch transfer", COURSE_TRANSFER: "Course transfer",
};

/** Enrolment changes available for a student state. */
export function availableChanges(status: StudentStatus | undefined, hasCurrent: boolean): EnrolmentChange[] {
  if (status === "ADMITTED" && !hasCurrent) return ["INITIAL"];
  if ((status === "ENROLLED" || status === "ACTIVE") && hasCurrent) return ["PROGRESSION", "PROMOTION", "BATCH_TRANSFER", "COURSE_TRANSFER"];
  return [];
}

export function needsReason(change: EnrolmentChange): boolean {
  return change === "BATCH_TRANSFER" || change === "COURSE_TRANSFER";
}

export const RELATIONSHIPS = ["MOTHER", "FATHER", "GUARDIAN", "GRANDPARENT", "SIBLING", "SPOUSE", "RELATIVE", "OTHER"] as const;

export function titleCase(code: string | undefined | null): string {
  if (!code) return "—";
  return code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, " ");
}

export function fileSize(bytes: number | undefined): string {
  if (bytes === undefined) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_TYPES = ["application/pdf", "image/jpeg", "image/png"];

/** Client-side pre-check (the server inspects the file content and decides). */
export function uploadProblem(file: { size: number; type: string }, category: string): string | null {
  if (file.size === 0) return "The file is empty.";
  if (file.size > MAX_UPLOAD_BYTES) return "Files can be at most 5 MB.";
  if (file.type && !ACCEPTED_TYPES.includes(file.type)) return "Upload a PDF, JPEG or PNG file.";
  if (category === "PHOTOGRAPH" && file.type === "application/pdf") return "A photograph must be a JPEG or PNG image.";
  return null;
}

export const SCAN_TEXT: Record<string, { label: string; tone: BadgeTone; help: string }> = {
  PENDING: { label: "Quarantined", tone: "warning", help: "Awaiting the malware scan; it cannot be opened until it is scanned clean." },
  CLEAN: { label: "Scanned clean", tone: "success", help: "Passed the malware scan." },
  INFECTED: { label: "Malware detected", tone: "danger", help: "Blocked. It will never be available and is purged after the retention period." },
  ERROR: { label: "Scan failed", tone: "danger", help: "The scan did not complete; it stays blocked until scanned again." },
};

/** Generated student ID preview for a template (mirrors the server's rendering). */
export function renderTemplate(template: string, inst: string, year: number, seq: number): string {
  return template.replace(/\{(INST|YYYY|YY|SEQ:([3-8]))}/g, (_m, token: string, width?: string) => {
    if (token === "INST") return inst.toUpperCase().replace(/[^A-Z0-9]/g, "");
    if (token === "YYYY") return String(year);
    if (token === "YY") return String(year % 100).padStart(2, "0");
    return String(seq).padStart(Number(width), "0");
  });
}
