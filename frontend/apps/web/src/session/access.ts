import type { MeResponse } from "@ivorovi/api-client";

/**
 * Client-side mirror of the server's scope rules, used ONLY to shape navigation and controls.
 * Every request is still authorised by the API; hiding a control is not a security boundary.
 */
export interface Access {
  has(permission: string): boolean;
  hasInInstitution(permission: string, institutionId: string | null | undefined): boolean;
  hasAnywhere(permission: string): boolean;
  institutionsWith(permission: string): string[];
}

export function createAccess(me: Pick<MeResponse, "grants" | "tenant">): Access {
  const grants = me.grants ?? [];
  const tenantId = me.tenant?.id;
  const has = (p: string) => grants.some((g) => g.permission === p && g.scopeType === "TENANT" && g.scopeId === tenantId);
  return {
    has,
    hasInInstitution: (p, inst) =>
      inst ? has(p) || grants.some((g) => g.permission === p && g.scopeType === "INSTITUTION" && g.scopeId === inst) : has(p),
    hasAnywhere: (p) => grants.some((g) => g.permission === p),
    institutionsWith: (p) => [...new Set(grants.filter((g) => g.permission === p && g.scopeType === "INSTITUTION").map((g) => g.scopeId!))],
  };
}
