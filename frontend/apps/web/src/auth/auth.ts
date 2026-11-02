import { InMemoryWebStorage, WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";
import type { AppConfig } from "../config";

export const CALLBACK_PATH = "/auth/callback";

/**
 * Authorization Code + PKCE (public client). Tokens are held in memory only; a page reload re-uses the IdP
 * session through a fast redirect instead of persisting tokens in browser storage.
 */
export function oidcSettings(config: AppConfig): UserManagerSettings {
  const origin = window.location.origin;
  return {
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: origin + CALLBACK_PATH,
    post_logout_redirect_uri: origin + "/",
    response_type: "code",
    scope: "openid profile email",
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  };
}

/** Only same-origin relative paths are accepted as post-login destinations (prevents open redirects). */
export function safeReturnPath(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.startsWith(CALLBACK_PATH)) return "/";
  return value;
}
