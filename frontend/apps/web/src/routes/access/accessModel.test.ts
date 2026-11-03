import type { PermissionDomain } from "@ivorovi/api-client";
import { describe, expect, it } from "vitest";
import { effectiveAccess, selectable } from "./accessModel";

const catalogue: PermissionDomain[] = [
  { domain: "USERS", label: "User management", permissions: [
    { code: "user.read", name: "View users", level: "READ", scope: "UNIT", availability: "AVAILABLE", heldByYou: true },
    { code: "user.manage", name: "Manage users", level: "MANAGE", scope: "UNIT", availability: "AVAILABLE", heldByYou: false },
  ] },
  { domain: "AUDIT", label: "Audit and administration", permissions: [
    { code: "audit.read", name: "View the audit trail", level: "READ", scope: "ORGANIZATION", availability: "AVAILABLE", heldByYou: true },
  ] },
  { domain: "STUDENTS", label: "Student management", permissions: [
    { code: "student.read", name: "View students", level: "READ", scope: "UNIT", availability: "PLANNED", heldByYou: false },
  ] },
];

describe("access model", () => {
  it("summarises the effective access of a selection by domain, with warnings", () => {
    const s = effectiveAccess(catalogue, ["user.read", "user.manage", "audit.read"]);
    expect(s.count).toBe(3);
    expect(s.domains.map((d) => d.label)).toEqual(["User management", "Audit and administration"]);
    expect(s.organizationOnly).toBe(true);
    expect(s.warnings.join(" ")).toMatch(/invite, suspend and deactivate/);
    expect(s.warnings.join(" ")).toMatch(/only be assigned for the whole organization/);
  });

  it("allows only available permissions the editor holds, and organization-only ones only for organization roles", () => {
    const [read, manage] = catalogue[0]!.permissions!;
    expect(selectable(read!, false).ok).toBe(true);
    expect(selectable(manage!, false)).toEqual({ ok: false, reason: "You do not hold this permission" });
    expect(selectable(catalogue[1]!.permissions![0]!, true).ok).toBe(false);
    expect(selectable(catalogue[2]!.permissions![0]!, false).reason).toMatch(/later module/);
  });
});
