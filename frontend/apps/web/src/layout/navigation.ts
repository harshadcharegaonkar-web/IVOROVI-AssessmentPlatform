import type { OrgUnitType } from "@ivorovi/api-client";
import type { TerminologyKey } from "../session/useOrgLabels";
import { Activity, FileQuestion, Inbox, Tags, ShieldCheck, ClipboardCheck, MonitorCheck, Siren, Stamp, BadgeCheck, Bot, Bell, ChartBar, ChartColumn, FileText, KeySquare, Plug, Webhook, BookKey, Contact, Gauge, FileUp, IdCard, BookOpen, CalendarDays, CalendarRange, GitBranch, GraduationCap, Layers, LibraryBig, KeyRound, Landmark, ListChecks, LayoutDashboard, type LucideIcon, MailPlus, MapPin, Network, School, ScrollText, Settings2, SlidersHorizontal, UserCog, UserRound, Users } from "lucide-react";
import type { Access } from "../session/access";

/** {@code orgType}: the label comes from the organization's configured plural for that unit type. */
export interface NavItem { to: string; label: string; icon: LucideIcon; visible: (a: Access) => boolean; orgType?: OrgUnitType | TerminologyKey }
export interface NavGroup { id: string; label: string | null; items: NavItem[] }

/**
 * Navigation model (design system §3). Only implemented screens are listed: groups gain items as increments
 * deliver them (People 1.2; Access 1.3; Academics 1.4; Students 1.5; Insights and Integrations 1.7).
 * Visibility mirrors server permissions for convenience only; the API enforces every permission.
 */
export const NAV_GROUPS: NavGroup[] = [
  { id: "home", label: null, items: [
    { to: "/", label: "Dashboard", icon: LayoutDashboard, visible: () => true },
    { to: "/approvals", label: "Approvals", icon: Stamp, visible: () => true },
  ] },
  {
    id: "insights", label: "Insights",
    items: [
      { to: "/dashboards/academic", label: "Academic Dashboard", icon: ChartColumn, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/dashboards/staff", label: "Staff Dashboard", icon: ChartBar, visible: (a) => a.hasAnywhere("staff.read") },
      { to: "/dashboards/imports", label: "Import Operations", icon: Activity, visible: (a) => a.hasAnywhere("student.import") },
      { to: "/reports", label: "Reports", icon: FileText, visible: (a) => a.hasAnywhere("report.read") },
    ],
  },
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
    id: "academics", label: "Academics",
    items: [
      { to: "/academics", label: "Academic Overview", icon: LibraryBig, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/programs", label: "Programs", orgType: "PROGRAM", icon: GraduationCap, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/courses", label: "Courses", orgType: "COURSE", icon: GitBranch, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/subjects", label: "Subjects", orgType: "SUBJECT", icon: BookOpen, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/calendar", label: "Academic Calendar", icon: CalendarRange, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/terms", label: "Semesters", orgType: "TERM", icon: CalendarDays, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/batches", label: "Batches", orgType: "BATCH", icon: Users, visible: (a) => a.hasAnywhere("academics.read") },
      { to: "/academics/offerings", label: "Subject Offerings", icon: Layers, visible: (a) => a.hasAnywhere("academics.read") },
    ],
  },
  {
    id: "students", label: "Students",
    items: [
      { to: "/students", label: "Student Dashboard", icon: Gauge, visible: (a) => a.hasAnywhere("student.read") },
      { to: "/students/list", label: "Students", icon: Contact, visible: (a) => a.hasAnywhere("student.read") },
      { to: "/students/imports", label: "Student Imports", icon: FileUp, visible: (a) => a.hasAnywhere("student.import") },
      { to: "/students/settings", label: "Student Settings", icon: IdCard, visible: (a) => a.hasAnywhere("student.read") },
    ],
  },
  {
    id: "questions", label: "Question Bank",
    items: [
      { to: "/questions", label: "Question Banks", icon: FileQuestion, visible: (a) => a.hasAnywhere("question.read") },
      { to: "/questions/review", label: "Review Queue", icon: Inbox, visible: (a) => a.hasAnywhere("question.review") || a.hasAnywhere("question.approve") },
      { to: "/questions/taxonomy", label: "Taxonomy", icon: Tags, visible: (a) => a.hasAnywhere("question.read") },
      { to: "/questions/policies", label: "Approval Policies", icon: ShieldCheck, visible: (a) => a.hasAnywhere("question.read") },
    ],
  },
  {
    id: "exams", label: "Examinations",
    items: [
      { to: "/exams/portals", label: "Exam Portals", icon: MonitorCheck, visible: (a) => a.hasAnywhere("exam.session.manage") },
      { to: "/exams/sessions", label: "Readiness Sessions", icon: ClipboardCheck, visible: (a) => a.hasAnywhere("exam.session.manage") },
      { to: "/exams/candidates", label: "Candidates", icon: UserRound, visible: (a) => a.has("exam.session.manage") },
      { to: "/exams/incidents", label: "Incidents", icon: Siren, visible: (a) => a.has("exam.incident.manage") || a.has("exam.incident.decide") },
    ],
  },
  {
    id: "configuration", label: "Configuration",
    items: [
      { to: "/settings/features", label: "Feature Settings", icon: SlidersHorizontal, visible: (a) => a.hasAnywhere("feature.read") },
      { to: "/integrations", label: "Integrations", icon: Plug, visible: (a) => a.has("integration.read") },
      { to: "/integrations/api-clients", label: "API Clients", icon: KeySquare, visible: (a) => a.has("integration.read") },
      { to: "/integrations/service-identities", label: "Service Identities", icon: Bot, visible: (a) => a.has("integration.read") },
      { to: "/integrations/webhooks", label: "Webhooks", icon: Webhook, visible: (a) => a.has("integration.read") },
      { to: "/audit", label: "Audit Logs", icon: ScrollText, visible: (a) => a.has("audit.read") },
      { to: "/settings/system", label: "System Settings", icon: Settings2, visible: (a) => a.has("tenant.settings.manage") },
    ],
  },
];

export const FOOTER_ITEMS: NavItem[] = [
  { to: "/notifications", label: "Notifications", icon: Bell, visible: () => true },
  { to: "/me", label: "My Access", icon: UserRound, visible: () => true },
];

export function visibleGroups(access: Access): NavGroup[] {
  return NAV_GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => i.visible(access)) })).filter((g) => g.items.length > 0);
}

/** Flat list (used by global search and tests). */
export function visibleNav(access: Access): NavItem[] {
  return [...visibleGroups(access).flatMap((g) => g.items), ...FOOTER_ITEMS.filter((i) => i.visible(access))];
}
