/**
 * Runtime configuration, loaded from /config.json so a single build serves SaaS, dedicated and on-premises
 * deployments (the web container writes this file from environment variables at start-up).
 * Validated by hand (no schema library on the start-up path, which keeps the initial bundle small).
 */
export interface AppConfig {
  oidcAuthority: string;
  oidcClientId: string;
  apiBaseUrl: string;
}

export function parseConfig(raw: unknown): AppConfig {
  const o = (raw ?? {}) as Record<string, unknown>;
  const authority = o["oidcAuthority"];
  const clientId = o["oidcClientId"];
  const apiBase = o["apiBaseUrl"];
  if (typeof authority !== "string" || !/^https?:\/\/[^\s]+$/.test(authority)) throw new Error("config.json: oidcAuthority must be an http(s) URL");
  if (typeof clientId !== "string" || clientId.length === 0) throw new Error("config.json: oidcClientId is required");
  if (typeof apiBase !== "string") throw new Error("config.json: apiBaseUrl must be a string");
  return { oidcAuthority: authority, oidcClientId: clientId, apiBaseUrl: apiBase };
}

export async function loadConfig(): Promise<AppConfig> {
  const response = await fetch("/config.json", { cache: "no-store" });
  if (!response.ok) throw new Error(`Configuration could not be loaded (HTTP ${response.status})`);
  return parseConfig(await response.json());
}
