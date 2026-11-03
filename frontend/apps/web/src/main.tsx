import "@fontsource-variable/inter";
import { ErrorState, Spinner, ToastProvider } from "@ivorovi/ui";
import { UserManager } from "oidc-client-ts";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { RouterProvider } from "react-router";
import { ApiProvider } from "./api/ApiProvider";
import { oidcSettings, safeReturnPath } from "./auth/auth";
import { RequireAuth } from "./auth/RequireAuth";
import { loadConfig } from "./config";
import { router } from "./router";
import { SessionProvider } from "./session/SessionProvider";
import "./styles.css";

const root = createRoot(document.getElementById("root")!);
root.render(<div className="flex min-h-screen items-center justify-center"><Spinner size="lg" label="Starting" /></div>);

loadConfig()
  .then(async (config) => {
    // Public page: accepting an invitation must work without signing in (and without loading the shell).
    if (window.location.pathname === "/invitation") {
      const { AcceptInvitationPage } = await import("./routes/people/AcceptInvitationPage");
      root.render(<StrictMode><AcceptInvitationPage apiBaseUrl={config.apiBaseUrl} /></StrictMode>);
      return;
    }
    const userManager = new UserManager(oidcSettings(config));
    root.render(
      <StrictMode>
        <AuthProvider
          userManager={userManager}
          onSigninCallback={(user) => {
            const state = user?.state as { returnTo?: unknown } | undefined;
            void router.navigate(safeReturnPath(state?.returnTo), { replace: true });
          }}
        >
          <ToastProvider>
            <RequireAuth>
              <ApiProvider baseUrl={config.apiBaseUrl} userManager={userManager}>
                <SessionProvider>
                  <RouterProvider router={router} />
                </SessionProvider>
              </ApiProvider>
            </RequireAuth>
          </ToastProvider>
        </AuthProvider>
      </StrictMode>,
    );
  })
  .catch((e: unknown) => {
    root.render(<div className="flex min-h-screen items-center justify-center"><ErrorState title="Ivorovi could not start" message={e instanceof Error ? e.message : String(e)} /></div>);
  });
