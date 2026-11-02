import { ErrorState, Spinner } from "@ivorovi/ui";
import { useEffect, useRef, type ReactNode } from "react";
import { hasAuthParams, useAuth } from "react-oidc-context";

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const started = useRef(false);

  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error && !hasAuthParams() && !started.current) {
      started.current = true;
      void auth.signinRedirect({ state: { returnTo: window.location.pathname + window.location.search } });
    }
  }, [auth]);

  if (auth.error) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <ErrorState title="Sign-in failed" message={auth.error.message} onRetry={() => void auth.signinRedirect()} />
      </div>
    );
  }
  if (!auth.isAuthenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner size="lg" label="Signing you in" />
      </div>
    );
  }
  return <>{children}</>;
}
