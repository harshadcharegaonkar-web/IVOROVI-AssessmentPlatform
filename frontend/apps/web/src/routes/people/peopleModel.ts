import type { BadgeTone } from "@ivorovi/ui";

export type AccountStatus = "INVITED" | "ACTIVE" | "SUSPENDED" | "DEACTIVATED";
export type InvitationState = "NONE" | "PENDING" | "EXPIRED" | "ACCEPTED" | "REVOKED";
export type StaffCategory = "FACULTY" | "DEPARTMENT_ADMIN" | "ACADEMIC_ADMIN" | "EVALUATOR" | "EXAM_CONTROLLER" | "PROCTOR"
  | "ADMISSIONS_OFFICER" | "PLACEMENT_OFFICER" | "ADMINISTRATIVE_STAFF";
export type EmploymentStatus = "ACTIVE" | "ON_LEAVE" | "SEPARATED";
export type EmploymentType = "PERMANENT" | "CONTRACT" | "VISITING" | "ADJUNCT";

export const ACCOUNT_STATUS: Record<AccountStatus, { label: string; tone: BadgeTone; hint: string }> = {
  INVITED: { label: "Invited", tone: "info", hint: "Has not signed in yet" },
  ACTIVE: { label: "Active", tone: "success", hint: "Can sign in" },
  SUSPENDED: { label: "Suspended", tone: "warning", hint: "Temporarily blocked from signing in" },
  DEACTIVATED: { label: "Deactivated", tone: "neutral", hint: "No longer has access" },
};

export const INVITATION_STATE: Record<InvitationState, { label: string; tone: BadgeTone }> = {
  NONE: { label: "Not invited", tone: "neutral" },
  PENDING: { label: "Pending", tone: "info" },
  EXPIRED: { label: "Expired", tone: "warning" },
  ACCEPTED: { label: "Accepted", tone: "success" },
  REVOKED: { label: "Revoked", tone: "neutral" },
};

export const STAFF_CATEGORIES: Record<StaffCategory, string> = {
  FACULTY: "Faculty",
  DEPARTMENT_ADMIN: "Department administrator",
  ACADEMIC_ADMIN: "Academic administrator",
  EVALUATOR: "Evaluator",
  EXAM_CONTROLLER: "Exam controller",
  PROCTOR: "Proctor",
  ADMISSIONS_OFFICER: "Admissions officer",
  PLACEMENT_OFFICER: "Placement officer",
  ADMINISTRATIVE_STAFF: "Other administrative staff",
};

export const EMPLOYMENT_STATUS: Record<EmploymentStatus, { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: "Active", tone: "success" },
  ON_LEAVE: { label: "On leave", tone: "warning" },
  SEPARATED: { label: "Separated", tone: "neutral" },
};

export const EMPLOYMENT_TYPES: Record<EmploymentType, string> = {
  PERMANENT: "Permanent", CONTRACT: "Contract", VISITING: "Visiting", ADJUNCT: "Adjunct",
};

/** Which status actions are offered for an account in a given state (the API enforces the same transitions). */
export function statusActions(status: AccountStatus): ("SUSPEND" | "REACTIVATE" | "DEACTIVATE" | "ACTIVATE")[] {
  switch (status) {
    case "ACTIVE": return ["SUSPEND", "DEACTIVATE"];
    case "SUSPENDED": return ["REACTIVATE", "DEACTIVATE"];
    case "INVITED": return ["DEACTIVATE"];
    case "DEACTIVATED": return ["ACTIVATE"];
  }
}

export const STATUS_ACTION_LABEL = { SUSPEND: "Suspend", REACTIVATE: "Reactivate", DEACTIVATE: "Deactivate", ACTIVATE: "Activate" } as const;
