import createClient, { type Middleware } from "openapi-fetch";
import { ApiError, toApiError } from "./errors";
import type { paths } from "./generated/schema";

export interface ApiClientOptions {
  baseUrl: string;
  /** Returns the current access token (kept in memory by the auth layer). */
  getAccessToken: () => string | undefined | Promise<string | undefined>;
  /** Called on 401 so the app can start a new sign-in. */
  onUnauthorized?: () => void;
  fetch?: typeof fetch;
}

export type IvoroviApi = ReturnType<typeof createApiClient>;

export function createApiClient(options: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl: options.baseUrl, ...(options.fetch ? { fetch: options.fetch } : {}) });
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await options.getAccessToken();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      return request;
    },
    async onResponse({ response }) {
      if (response.status === 401) options.onUnauthorized?.();
      if (!response.ok) throw await toApiError(response);
      return response;
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
