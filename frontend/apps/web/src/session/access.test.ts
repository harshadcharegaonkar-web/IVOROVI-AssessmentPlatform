import { describe, expect, it } from "vitest";
import { visibleGroups, visibleNav } from "../layout/navigation";
import { createAccess } from "./access";

const tenant = { id: "t1", code: "aurora", name: "Aurora" };
const orgAdmin = createAccess({ tenant, grants: [
  { permission: "org_unit.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
  { permission: "audit.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
  { permission: "feature.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
  { permission: "tenant.settings.manage", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
] });
const instAdmin = createAccess({ tenant, grants: [
  { permission: "feature.manage", role: "INSTITUTION_ADMIN", scopeType: "ORG_UNIT", scopeId: "i1" },
  { permission: "feature.read", role: "INSTITUTION_ADMIN", scopeType: "ORG_UNIT", scopeId: "i1" },
] });
const student = createAccess({ tenant, grants: [] });

describe("access mirror", () => {
  it("applies tenant-wide grants to every institution", () => {
    expect(orgAdmin.has("audit.read")).toBe(true);
    expect(orgAdmin.hasInOrgUnit("org_unit.read", "any")).toBe(true);
  });

  it("confines unit grants to the unit's subtree", () => {
    expect(instAdmin.has("feature.manage")).toBe(false);
    expect(instAdmin.hasInOrgUnit("feature.manage", "i1")).toBe(true);
    expect(instAdmin.hasInOrgUnit("feature.manage", "dept-7", ["univ", "i1", "campus-3"])).toBe(true);
    expect(instAdmin.hasInOrgUnit("feature.manage", "i2", ["univ"])).toBe(false);
    expect(instAdmin.hasInOrgUnit("feature.manage", null)).toBe(false);
    expect(instAdmin.orgUnitScopesWith("feature.manage")).toEqual(["i1"]);
  });

  it("ignores grants scoped to another tenant id", () => {
    const forged = createAccess({ tenant, grants: [{ permission: "audit.read", role: "X", scopeType: "TENANT", scopeId: "t2" }] });
    expect(forged.has("audit.read")).toBe(false);
  });

  it("derives navigation from grants", () => {
    expect(visibleNav(orgAdmin).map((n) => n.label)).toEqual(["Dashboard", "Organization Hierarchy", "Universities", "Colleges", "Campuses", "Departments",
      "Feature Settings", "Audit Logs", "System Settings", "Notifications", "My Access"]);
    expect(visibleNav(instAdmin).map((n) => n.label)).toEqual(["Dashboard", "Feature Settings", "Notifications", "My Access"]);
    expect(visibleNav(student).map((n) => n.label)).toEqual(["Dashboard", "Notifications", "My Access"]);
  });

  it("hides navigation groups that have no permitted items", () => {
    expect(visibleGroups(student).map((g) => g.id)).toEqual(["home"]);
    expect(visibleGroups(instAdmin).map((g) => g.id)).toEqual(["home", "configuration"]);
  });
});
