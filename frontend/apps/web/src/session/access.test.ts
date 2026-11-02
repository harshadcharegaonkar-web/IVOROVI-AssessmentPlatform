import { describe, expect, it } from "vitest";
import { visibleGroups, visibleNav } from "../layout/navigation";
import { createAccess } from "./access";

const tenant = { id: "t1", code: "aurora", name: "Aurora" };
const orgAdmin = createAccess({ tenant, grants: [
  { permission: "institution.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
  { permission: "audit.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
  { permission: "feature.read", role: "ORG_ADMIN", scopeType: "TENANT", scopeId: "t1" },
] });
const instAdmin = createAccess({ tenant, grants: [
  { permission: "feature.manage", role: "INSTITUTION_ADMIN", scopeType: "INSTITUTION", scopeId: "i1" },
  { permission: "feature.read", role: "INSTITUTION_ADMIN", scopeType: "INSTITUTION", scopeId: "i1" },
] });
const student = createAccess({ tenant, grants: [] });

describe("access mirror", () => {
  it("applies tenant-wide grants to every institution", () => {
    expect(orgAdmin.has("audit.read")).toBe(true);
    expect(orgAdmin.hasInInstitution("institution.read", "any")).toBe(true);
  });

  it("confines institution grants to their institution", () => {
    expect(instAdmin.has("feature.manage")).toBe(false);
    expect(instAdmin.hasInInstitution("feature.manage", "i1")).toBe(true);
    expect(instAdmin.hasInInstitution("feature.manage", "i2")).toBe(false);
    expect(instAdmin.hasInInstitution("feature.manage", null)).toBe(false);
    expect(instAdmin.institutionsWith("feature.manage")).toEqual(["i1"]);
  });

  it("ignores grants scoped to another tenant id", () => {
    const forged = createAccess({ tenant, grants: [{ permission: "audit.read", role: "X", scopeType: "TENANT", scopeId: "t2" }] });
    expect(forged.has("audit.read")).toBe(false);
  });

  it("derives navigation from grants", () => {
    expect(visibleNav(orgAdmin).map((n) => n.label)).toEqual(["Dashboard", "Institution Management", "Feature Settings", "Audit Logs", "My Access"]);
    expect(visibleNav(instAdmin).map((n) => n.label)).toEqual(["Dashboard", "Feature Settings", "My Access"]);
    expect(visibleNav(student).map((n) => n.label)).toEqual(["Dashboard", "My Access"]);
  });

  it("hides navigation groups that have no permitted items", () => {
    expect(visibleGroups(student).map((g) => g.id)).toEqual(["home"]);
    expect(visibleGroups(instAdmin).map((g) => g.id)).toEqual(["home", "configuration"]);
  });
});
