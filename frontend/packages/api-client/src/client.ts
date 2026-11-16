import createClient, { type Middleware } from "openapi-fetch";
import { ApiError, toApiError } from "./errors";
import type { paths } from "./generated/schema";

export interface ApiClientOptions {
  baseUrl: string;
  /** Returns the current access token (kept in memory by the auth layer). */
  getAccessToken: () => string | undefined | Promise<string | undefined>;
  /**
   * Called on 401, and on 403 {@code mfa_required}, so the app can start a new sign-in. The error carries the reason: the
   * organization's security policy codes ({@link REAUTHENTICATION_CODES}) need a fresh login, not a silent token renewal.
   */
  onUnauthorized?: (error: ApiError) => void;
  fetch?: typeof fetch;
}

export type IvoroviApi = ReturnType<typeof createApiClient>;

/** Refusals by the organization security policy: the identity provider's session must be replaced by a new sign-in. */
export const REAUTHENTICATION_CODES: ReadonlySet<string> = new Set(["mfa_required", "session_idle_timeout", "session_expired", "session_untracked"]);

export function createApiClient(options: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl: options.baseUrl, ...(options.fetch ? { fetch: options.fetch } : {}) });
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await options.getAccessToken();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      return request;
    },
    async onResponse({ response }) {
      if (response.ok) return response;
      const error = await toApiError(response);
      if (response.status === 401 || (response.status === 403 && error.code === "mfa_required")) options.onUnauthorized?.(error);
      throw error;
    },
    onError({ error }) {
      if (error instanceof ApiError) return error;
      return new ApiError(0, undefined);
    },
  };
  client.use(auth);
  return client;
}

/** Unwraps an openapi-fetch result: returns data or throws the ApiError. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined) throw result.error instanceof ApiError ? result.error : new ApiError(result.response.status, undefined);
  return result.data as T;
}
