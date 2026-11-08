import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import type { ExamConfig } from "./config";

export const CALLBACK_PATH = "/auth/callback";

/**
 * Candidate realm only (Q-03): its own client (`ivorovi-exam`, audience `ivorovi-delivery`), Authorization Code + PKCE.
 * Tokens live in memory; nothing is shared with the staff application (different origin, client and realm).
 */
export function createUserManager(config: ExamConfig): UserManager {
  const origin = window.location.origin;
  return new UserManager({
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: origin + CALLBACK_PATH,
    post_logout_redirect_uri: origin + "/",
    response_type: "code",
    scope: "openid email profile",
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    // only the short-lived PKCE/state record of the redirect is kept, in this origin's session storage
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true, // refresh-token grant (no iframe; CSP has no frame-src)
    monitorSession: false,
  });
}

/** Return path after sign-in: only a portal path of this application. */
export function safeReturnPath(value: unknown): string {
  return typeof value === "string" && /^\/p\/[a-z0-9][a-z0-9-]{2,39}(\/[a-z-]*)?$/.test(value) ? value : "/";
}

export type Session = { user: User | null };
