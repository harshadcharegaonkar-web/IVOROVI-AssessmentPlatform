import type { MeResponse } from "@ivorovi/api-client";

/**
 * Client-side mirror of the server's scope rules, used ONLY to shape navigation and controls.
 * Every request is still authorised by the API; hiding a control is not a security boundary.
 */
export interface Access {
  has(permission: string): boolean;
  /** Granted for a unit: tenant-wide, on the unit, or on one of its ancestors (pass the unit's ancestor ids). */
  hasInOrgUnit(permission: string, orgUnitId: string | null | undefined, ancestorIds?: string[]): boolean;
  hasAnywhere(permission: string): boolean;
  orgUnitScopesWith(permission: string): string[];
}

export function createAccess(me: Pick<MeResponse, "grants" | "tenant">): Access {
  const grants = me.grants ?? [];
  const tenantId = me.tenant?.id;
  const has = (p: string) => grants.some((g) => g.permission === p && g.scopeType === "TENANT" && g.scopeId === tenantId);
  return {
    has,
    hasInOrgUnit: (p, unit, ancestors = []) => {
      if (has(p)) return true;
      if (!unit) return false;
      const scope = new Set([unit, ...ancestors]);
      return grants.some((g) => g.permission === p && g.scopeType === "ORG_UNIT" && scope.has(g.scopeId!));
    },
    hasAnywhere: (p) => grants.some((g) => g.permission === p),
    orgUnitScopesWith: (p) => [...new Set(grants.filter((g) => g.permission === p && g.scopeType === "ORG_UNIT").map((g) => g.scopeId!))],
  };
}
