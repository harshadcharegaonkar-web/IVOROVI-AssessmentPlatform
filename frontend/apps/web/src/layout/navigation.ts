import type { OrgUnitType } from "@ivorovi/api-client";
import { BadgeCheck, BookKey, KeyRound, Landmark, ListChecks, LayoutDashboard, type LucideIcon, MailPlus, MapPin, Network, School, ScrollText, Settings2, SlidersHorizontal, UserCog, UserRound, Users } from "lucide-react";
import type { Access } from "../session/access";

/** {@code orgType}: the label comes from the organization's configured plural for that unit type. */
export interface NavItem { to: string; label: string; icon: LucideIcon; visible: (a: Access) => boolean; orgType?: OrgUnitType }
export interface NavGroup { id: string; label: string | null; items: NavItem[] }

/**
 * Navigation model (design system §3). Only implemented screens are listed: groups gain items as increments
 * deliver them (People 1.2; Access 1.3; Academics and Students later).
 * Visibility mirrors server permissions for convenience only; the API enforces every permission.
 */
export const NAV_GROUPS: NavGroup[] = [
  { id: "home", label: null, items: [{ to: "/", label: "Dashboard", icon: LayoutDashboard, visible: () => true }] },
  {
    id: "organization", label: "Organization",
    items: [
      { to: "/organization", label: "Organization Hierarchy", icon: Network, visible: (a) => a.hasAnywhere("org_unit.read") },
      { to: "/organization/universities", label: "Universities", orgType: "UNIVERSITY", icon: Landmark, visible: (a) => a.hasAnywhere("org_unit.read") },
      { to: "/organization/colleges", label: "Colleges", orgType: "COLLEGE", icon: School, visible: (a) => a.hasAnywhere("org_unit.read") },
      { to: "/organization/campuses", label: "Campuses", orgType: "CAMPUS", icon: MapPin, visible: (a) => a.hasAnywhere("org_unit.read") },
      { to: "/organization/departments", label: "Departments", orgType: "DEPARTMENT", icon: Users, visible: (a) => a.hasAnywhere("org_unit.read") },
    ],
  },
  {
    id: "people", label: "People",
    items: [
      { to: "/people/users", label: "Users", icon: UserCog, visible: (a) => a.hasAnywhere("user.read") },
      { to: "/people/staff", label: "Staff", icon: BadgeCheck, visible: (a) => a.hasAnywhere("staff.read") },
      { to: "/people/invitations", label: "Invitations", icon: MailPlus, visible: (a) => a.hasAnywhere("user.read") },
    ],
  },
  {
    id: "access", label: "Access",
    items: [
      { to: "/access/roles", label: "Roles", icon: KeyRound, visible: (a) => a.hasAnywhere("role.read") },
      { to: "/access/permissions", label: "Permission Catalogue", icon: BookKey, visible: (a) => a.hasAnywhere("role.read") },
      { to: "/access/assignments", label: "Role Assignments", icon: ListChecks, visible: (a) => a.hasAnywhere("role.read") },
    ],
  },
  {
    id: "configuration", label: "Configuration",
    items: [
      { to: "/settings/features", label: "Feature Settings", icon: SlidersHorizontal, visible: (a) => a.hasAnywhere("feature.read") },
      { to: "/audit", label: "Audit Logs", icon: ScrollText, visible: (a) => a.has("audit.read") },
      { to: "/settings/system", label: "System Settings", icon: Settings2, visible: (a) => a.has("tenant.settings.manage") },
    ],
  },
];

export const FOOTER_ITEMS: NavItem[] = [{ to: "/me", label: "My Access", icon: UserRound, visible: () => true }];

export function visibleGroups(access: Access): NavGroup[] {
  return NAV_GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => i.visible(access)) })).filter((g) => g.items.length > 0);
}

/** Flat list (used by global search and tests). */
export function visibleNav(access: Access): NavItem[] {
  return [...visibleGroups(access).flatMap((g) => g.items), ...FOOTER_ITEMS.filter((i) => i.visible(access))];
}
