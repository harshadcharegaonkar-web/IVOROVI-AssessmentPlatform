import { Building2, LayoutDashboard, type LucideIcon, ScrollText, SlidersHorizontal, UserRound } from "lucide-react";
import type { Access } from "../session/access";

export interface NavItem { to: string; label: string; icon: LucideIcon; visible: (a: Access) => boolean }
export interface NavGroup { id: string; label: string | null; items: NavItem[] }

/**
 * Navigation model (design system §3). Only implemented screens are listed: groups gain items as increments
 * deliver them (Organization: campuses/departments in 1.1; Academics 1.2/1.5; People 1.3/1.6; Access 1.4).
 * Visibility mirrors server permissions for convenience only; the API enforces every permission.
 */
export const NAV_GROUPS: NavGroup[] = [
  { id: "home", label: null, items: [{ to: "/", label: "Dashboard", icon: LayoutDashboard, visible: () => true }] },
  {
    id: "organization", label: "Organization",
    items: [{ to: "/institutions", label: "Institution Management", icon: Building2, visible: (a) => a.hasAnywhere("institution.read") }],
  },
  {
    id: "configuration", label: "Configuration",
    items: [
      { to: "/settings/features", label: "Feature Settings", icon: SlidersHorizontal, visible: (a) => a.hasAnywhere("feature.read") },
      { to: "/audit", label: "Audit Logs", icon: ScrollText, visible: (a) => a.has("audit.read") },
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
