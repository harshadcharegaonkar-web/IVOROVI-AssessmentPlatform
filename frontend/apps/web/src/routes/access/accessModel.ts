import type { PermissionDomain, PermissionInfo } from "@ivorovi/api-client";
import type { BadgeTone } from "@ivorovi/ui";

export const LEVEL: Record<string, { label: string; tone: BadgeTone }> = {
  READ: { label: "View", tone: "neutral" },
  WRITE: { label: "Act", tone: "info" },
  MANAGE: { label: "Manage", tone: "warning" },
};

export const SCOPE_RULE: Record<string, string> = {
  ORGANIZATION: "Whole organization only",
  UNIT: "Organization or a unit and below",
};

/** Plain-language sentence describing where a role's permissions apply. */
export function scopePhrase(scopeName: string | undefined, unitLimited: boolean): string {
  return unitLimited ? `within ${scopeName} and everything below it` : "for the whole organization, or for the unit it is assigned to";
}

export interface EffectiveSummary {
  domains: { label: string; permissions: PermissionInfo[] }[];
  count: number;
  organizationOnly: boolean;
  warnings: string[];
}

/** What a selection of permission codes allows, grouped by domain, with warnings for powerful permissions. */
export function effectiveAccess(catalogue: PermissionDomain[] | undefined, selected: Iterable<string>): EffectiveSummary {
  const chosen = new Set(selected);
  const domains = (catalogue ?? [])
    .map((d) => ({ label: d.label ?? "", permissions: (d.permissions ?? []).filter((p) => chosen.has(p.code ?? "")) }))
    .filter((d) => d.permissions.length > 0);
  const all = domains.flatMap((d) => d.permissions);
  const warnings: string[] = [];
  if (chosen.has("role.manage") || chosen.has("role.assign")) warnings.push("Holders can create or assign roles, but only with permissions they hold themselves.");
  if (chosen.has("user.manage")) warnings.push("Holders can invite, suspend and deactivate user accounts in their scope.");
  if (chosen.has("org_unit.manage")) warnings.push("Holders can restructure, deactivate and delete organization units in their scope.");
  if (chosen.has("feature.manage")) warnings.push("Holders can switch modules and AI capabilities on or off in their scope.");
  const organizationOnly = all.some((p) => p.scope === "ORGANIZATION");
  if (organizationOnly) warnings.push("Contains organization-wide permissions, so the role can only be assigned for the whole organization.");
  return { domains, count: all.length, organizationOnly, warnings };
}

/** Codes that a role editor may select: available and held by the editor (the server enforces the same rule). */
export function selectable(p: PermissionInfo, unitLimited: boolean): { ok: boolean; reason?: string } {
  if (p.availability === "PLANNED") return { ok: false, reason: "Coming with a later module" };
  if (unitLimited && p.scope === "ORGANIZATION") return { ok: false, reason: "Only for organization-wide roles" };
  if (!p.heldByYou) return { ok: false, reason: "You do not hold this permission" };
  return { ok: true };
}
