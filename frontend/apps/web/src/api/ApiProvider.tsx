import { ApiError, createApiClient, REAUTHENTICATION_CODES, type IvoroviApi } from "@ivorovi/api-client";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { UserManager } from "oidc-client-ts";
import { createContext, useContext, useMemo, type ReactNode } from "react";

const ApiContext = createContext<IvoroviApi | null>(null);

function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
  return failureCount < 2;
}

export function ApiProvider({ baseUrl, userManager, children }: { baseUrl: string; userManager: UserManager; children: ReactNode }) {
  const api = useMemo(() => createApiClient({
    baseUrl,
    getAccessToken: async () => (await userManager.getUser())?.access_token,
    // The organization's security policy (second factor, session limits) ended this session at the identity provider's
    // level: a silent renewal would return the same session, so force a new sign-in (prompt=login).
    onUnauthorized: (error) => void userManager.signinRedirect({
      state: { returnTo: window.location.pathname },
      ...(REAUTHENTICATION_CODES.has(error.code) ? { prompt: "login" } : {}),
    }),
  }), [baseUrl, userManager]);

  const queryClient = useMemo(() => new QueryClient({
    queryCache: new QueryCache(),
    mutationCache: new MutationCache(),
    defaultOptions: {
      queries: { retry: shouldRetry, staleTime: 30_000, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  }), []);

  return (
    <ApiContext.Provider value={api}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ApiContext.Provider>
  );
}

export function useApi(): IvoroviApi {
  const api = useContext(ApiContext);
  if (!api) throw new Error("useApi must be used inside <ApiProvider>");
  return api;
}

export function errorInfo(error: unknown): { message: string; traceId?: string | undefined } {
  if (error instanceof ApiError) return { message: error.message, traceId: error.traceId };
  return { message: "An unexpected error occurred." };
}
