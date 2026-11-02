import { unwrap, type MeResponse } from "@ivorovi/api-client";
import { applyBrandColor, Button, ErrorState, Spinner } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, type ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { errorInfo, useApi } from "../api/ApiProvider";
import { createAccess, type Access } from "./access";

interface Session { me: MeResponse; access: Access }
const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const auth = useAuth();
  const me = useQuery({ queryKey: ["me"], queryFn: async () => unwrap(await api.GET("/api/v1/me")) });

  useEffect(() => {
    const branding = me.data?.tenant?.branding as Record<string, unknown> | undefined;
    if (branding) applyBrandColor(branding["primaryColor"]);
    const name = branding?.["displayName"];
    document.title = typeof name === "string" ? `${name} · Ivorovi` : "Ivorovi";
  }, [me.data]);

  const session = useMemo(() => (me.data ? { me: me.data, access: createAccess(me.data) } : null), [me.data]);

  if (me.isPending) {
    return <div className="flex min-h-screen items-center justify-center"><Spinner size="lg" label="Loading your workspace" /></div>;
  }
  if (me.isError || !session) {
    const info = errorInfo(me.error);
    return (
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="text-center">
          <ErrorState title="We could not open your workspace" message={info.message} traceId={info.traceId} onRetry={() => void me.refetch()} />
          <Button variant="ghost" onClick={() => void auth.signoutRedirect()}>Sign out</Button>
        </div>
      </div>
    );
  }
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const s = useContext(SessionContext);
  if (!s) throw new Error("useSession must be used inside <SessionProvider>");
  return s;
}
