import type { OrgUnitType } from "@ivorovi/api-client";

/**
 * Client mirror of the server's hierarchy rules, used only to offer valid choices in forms.
 * The API validates every change independently (invalid_hierarchy, hierarchy_cycle, ...).
 */
export const UNIT_TYPES: OrgUnitType[] = ["UNIVERSITY", "COLLEGE", "CAMPUS", "DEPARTMENT"];

export const ALLOWED_PARENTS: Record<OrgUnitType, OrgUnitType[]> = {
  UNIVERSITY: [],
  COLLEGE: ["UNIVERSITY"],
  CAMPUS: ["UNIVERSITY", "COLLEGE"],
  DEPARTMENT: ["UNIVERSITY", "COLLEGE", "CAMPUS"],
};

export const MAY_BE_TOP_LEVEL: Record<OrgUnitType, boolean> = { UNIVERSITY: true, COLLEGE: true, CAMPUS: false, DEPARTMENT: false };

export const isInstitution = (t: OrgUnitType | undefined) => t === "UNIVERSITY" || t === "COLLEGE";

export const TYPE_ROUTES: Record<OrgUnitType, string> = {
  UNIVERSITY: "universities",
  COLLEGE: "colleges",
  CAMPUS: "campuses",
  DEPARTMENT: "departments",
};

export const ROUTE_TYPES: Record<string, OrgUnitType> = {
  universities: "UNIVERSITY",
  colleges: "COLLEGE",
  campuses: "CAMPUS",
  departments: "DEPARTMENT",
};

export interface ParentCandidate { id: string; type: OrgUnitType; status?: string | undefined; parentId?: string | null | undefined }

/** Parents a unit of {@code type} may be placed under, excluding the unit itself and its own subtree (moves). */
export function parentOptions<T extends ParentCandidate>(type: OrgUnitType, candidates: T[], movingId?: string): T[] {
  const excluded = new Set<string>();
  if (movingId) {
    excluded.add(movingId);
    let grew = true;
    while (grew) {
      grew = false;
      for (const c of candidates) {
        if (c.parentId && excluded.has(c.parentId) && !excluded.has(c.id)) { excluded.add(c.id); grew = true; }
      }
    }
  }
  return candidates.filter((c) => ALLOWED_PARENTS[type].includes(c.type) && c.status !== "INACTIVE" && !excluded.has(c.id));
}
