import { createBrowserRouter, Navigate, useLocation } from "react-router";
import { useAuth } from "react-oidc-context";
import { Spinner } from "@ivorovi/ui";
import { CALLBACK_PATH } from "./auth/auth";
import { AppShell } from "./layout/AppShell";
import { AuditPage } from "./routes/AuditPage";
import { DashboardPage } from "./routes/DashboardPage";
import { FeaturesPage } from "./routes/features/FeaturesPage";
import { NotFoundPage, RequirePermission } from "./routes/Guards";
import { InstitutionDetailPage } from "./routes/InstitutionDetailPage";
import { InstitutionsPage } from "./routes/InstitutionsPage";
import { MyAccessPage } from "./routes/MyAccessPage";

function AuthCallback() {
  const auth = useAuth();
  const location = useLocation();
  if (auth.isAuthenticated) return <Navigate to="/" replace state={location.state} />;
  return <div className="flex min-h-screen items-center justify-center"><Spinner size="lg" label="Completing sign-in" /></div>;
}

export const router = createBrowserRouter([
  { path: CALLBACK_PATH, element: <AuthCallback /> },
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "institutions", element: <RequirePermission check={(a) => a.hasAnywhere("institution.read")}><InstitutionsPage /></RequirePermission> },
      { path: "institutions/:id", element: <RequirePermission check={(a) => a.hasAnywhere("institution.read")}><InstitutionDetailPage /></RequirePermission> },
      { path: "settings/features", element: <RequirePermission check={(a) => a.hasAnywhere("feature.read")}><FeaturesPage /></RequirePermission> },
      { path: "audit", element: <RequirePermission check={(a) => a.has("audit.read")}><AuditPage /></RequirePermission> },
      { path: "me", element: <MyAccessPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
]);
