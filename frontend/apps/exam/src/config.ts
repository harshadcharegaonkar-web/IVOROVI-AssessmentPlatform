/** Runtime configuration (/config.json, written by the exam container from environment variables). */
export interface ExamConfig {
  oidcAuthority: string;
  oidcClientId: string;
}

export function parseConfig(raw: unknown): ExamConfig {
  const o = (raw ?? {}) as Record<string, unknown>;
  const authority = o["oidcAuthority"];
  const clientId = o["oidcClientId"];
  if (typeof authority !== "string" || !/^https?:\/\/[^\s]+$/.test(authority)) throw new Error("config.json: oidcAuthority must be an http(s) URL");
  if (typeof clientId !== "string" || clientId.length === 0) throw new Error("config.json: oidcClientId is required");
  return { oidcAuthority: authority.replace(/\/$/, ""), oidcClientId: clientId };
}

export async function loadConfig(): Promise<ExamConfig> {
  const r = await fetch("/config.json", { cache: "no-store" });
  if (!r.ok) throw new Error(`Configuration could not be loaded (HTTP ${r.status})`);
  return parseConfig(await r.json());
}

/** Portal key from the path (/p/{key}...): the server resolves it to the institution; the browser never chooses a tenant id. */
export function portalKeyFromPath(path: string): string | null {
  const m = /^\/p\/([a-z0-9][a-z0-9-]{2,39})(?:\/|$)/.exec(path);
  return m ? m[1]! : null;
}
