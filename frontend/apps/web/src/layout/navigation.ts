import { Building2, LayoutDashboard, type LucideIcon, ScrollText, SlidersHorizontal, UserRound } from "lucide-react";
import type { Access } from "../session/access";

export interface NavItem { to: string; label: string; icon: LucideIcon; visible: (a: Access) => boolean }

/** Navigation is derived from the caller's grants; the server enforces every permission independently. */
export const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, visible: () => true },
  { to: "/institutions", label: "Institutions", icon: Building2, visible: (a) => a.hasAnywhere("institution.read") },
  { to: "/settings/features", label: "Features", icon: SlidersHorizontal, visible: (a) => a.hasAnywhere("feature.read") },
  { to: "/audit", label: "Audit log", icon: ScrollText, visible: (a) => a.has("audit.read") },
  { to: "/me", label: "My access", icon: UserRound, visible: () => true },
];

export function visibleNav(access: Access): NavItem[] {
  return NAV_ITEMS.filter((i) => i.visible(access));
}
