import { ErrorState, Spinner } from "@ivorovi/ui";
import { useEffect } from "react";
import { createBrowserRouter, Navigate, useNavigate, useRouteError, type RouteObject } from "react-router";
import { CALLBACK_PATH } from "./auth/auth";
import { AppShell } from "./layout/AppShell";
import { NotFoundPage, RequirePermission } from "./routes/Guards";
import type { Access } from "./session/access";

/**
 * The OIDC callback is completed by AuthProvider, whose onSigninCallback navigates to the saved return path.
 * This route must not navigate on its own (a lazy target route may still be loading); it only falls back to
 * the dashboard if nothing navigated away within a few seconds (e.g. a bookmarked callback URL).
 */
function AuthCallback() {
  const navigate = useNavigate();
  useEffect(() => {
    const t = window.setTimeout(() => void navigate("/", { replace: true }), 5000);
    return () => window.clearTimeout(t);
  }, [navigate]);
  return <div className="flex min-h-screen items-center justify-center"><Spinner size="lg" label="Completing sign-in" /></div>;
}

const RELOAD_FLAG = "ivorovi.chunk-reload";

/** Route error boundary. A failed lazy chunk (typically after a deployment) triggers one automatic reload. */
export function RouteError() {
  const error = useRouteError();
  const message = error instanceof Error ? error.message : String(error);
  const chunkFailure = /dynamically imported module|Importing a module script failed|error loading dynamically/i.test(message);
  if (chunkFailure) {
    let reloaded = false;
    try { reloaded = sessionStorage.getItem(RELOAD_FLAG) === "1"; sessionStorage.setItem(RELOAD_FLAG, "1"); } catch { /* storage unavailable */ }
    if (!reloaded) {
      window.location.reload();
      return <div className="flex min-h-[50vh] items-center justify-center"><Spinner size="lg" label="Updating the application" /></div>;
    }
  }
  return <ErrorState title="This page could not be displayed"
    message={chunkFailure ? "A new version of Ivorovi is available. Reload the page to continue." : "An unexpected error occurred."}
    onRetry={() => window.location.reload()} />;
}

/** Lazy route module with an optional client-side permission guard (UX only; the API enforces access). */
function page(load: () => Promise<{ default: React.ComponentType }>, check?: (a: Access) => boolean): Pick<RouteObject, "lazy"> {
  return {
    lazy: async () => {
      const { default: Page } = await load();
      try { sessionStorage.removeItem(RELOAD_FLAG); } catch { /* ignore */ }
      return { Component: check ? () => <RequirePermission check={check}><Page /></RequirePermission> : Page };
    },
  };
}

export const routes: RouteObject[] = [
  { path: CALLBACK_PATH, element: <AuthCallback /> },
  {
    path: "/",
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { errorElement: <RouteError />, children: [
        { index: true, handle: { crumb: "Dashboard" }, ...page(() => import("./routes/DashboardPage")) },
        { path: "organization", handle: { crumb: "Organization" }, children: [
          { index: true, ...page(() => import("./routes/organization/OrgHierarchyPage"), (a) => a.hasAnywhere("org_unit.read")) },
          { path: "units/:id", handle: { crumb: "Details" }, ...page(() => import("./routes/organization/OrgUnitDetailPage"), (a) => a.hasAnywhere("org_unit.read")) },
          { path: ":typeSlug", ...page(() => import("./routes/organization/OrgUnitListPage"), (a) => a.hasAnywhere("org_unit.read")) },
        ] },
        { path: "people", handle: { crumb: "People" }, children: [
          { index: true, element: <Navigate to="/people/users" replace /> },
          { path: "users", handle: { crumb: "Users" }, children: [
            { index: true, ...page(() => import("./routes/people/UserListPage"), (a) => a.hasAnywhere("user.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/people/UserDetailPage"), (a) => a.hasAnywhere("user.read")) },
          ] },
          { path: "staff", handle: { crumb: "Staff" }, children: [
            { index: true, ...page(() => import("./routes/people/StaffListPage"), (a) => a.hasAnywhere("staff.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/people/StaffDetailPage"), (a) => a.hasAnywhere("staff.read")) },
          ] },
          { path: "invitations", handle: { crumb: "Invitations" }, ...page(() => import("./routes/people/InvitationsPage"), (a) => a.hasAnywhere("user.read")) },
        ] },
        { path: "access", handle: { crumb: "Access" }, children: [
          { index: true, element: <Navigate to="/access/roles" replace /> },
          { path: "roles", handle: { crumb: "Roles" }, children: [
            { index: true, ...page(() => import("./routes/access/RoleListPage"), (a) => a.hasAnywhere("role.read")) },
            { path: "new", handle: { crumb: "New role" }, ...page(() => import("./routes/access/RoleEditorPage"), (a) => a.hasAnywhere("role.manage")) },
            { path: "compare", handle: { crumb: "Compare" }, ...page(() => import("./routes/access/RoleComparePage"), (a) => a.hasAnywhere("role.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/access/RoleDetailPage"), (a) => a.hasAnywhere("role.read")) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(() => import("./routes/access/RoleEditorPage"), (a) => a.hasAnywhere("role.manage")) },
          ] },
          { path: "permissions", handle: { crumb: "Permission Catalogue" }, ...page(() => import("./routes/access/PermissionCataloguePage"), (a) => a.hasAnywhere("role.read")) },
          { path: "assignments", handle: { crumb: "Role Assignments" }, ...page(() => import("./routes/access/RoleAssignmentsPage"), (a) => a.hasAnywhere("role.read")) },
        ] },
        { path: "settings/features", handle: { crumb: "Feature Settings" }, ...page(() => import("./routes/features/FeaturesPage"), (a) => a.hasAnywhere("feature.read")) },
        { path: "settings/system", handle: { crumb: "System Settings" }, ...page(() => import("./routes/organization/SystemSettingsPage"), (a) => a.has("tenant.settings.manage")) },
        { path: "audit", handle: { crumb: "Audit Logs" }, ...page(() => import("./routes/AuditPage"), (a) => a.has("audit.read")) },
        { path: "me", handle: { crumb: "My Access" }, ...page(() => import("./routes/MyAccessPage")) },
        { path: "*", element: <NotFoundPage /> },
      ] },
    ],
  },
];

export const router = createBrowserRouter(routes);
