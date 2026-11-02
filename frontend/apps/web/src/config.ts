import { z } from "zod";

/**
 * Runtime configuration, loaded from /config.json so a single build serves SaaS, dedicated and on-premises
 * deployments (the web container writes this file from environment variables at start-up).
 */
const ConfigSchema = z.object({
  oidcAuthority: z.url(),
  oidcClientId: z.string().min(1),
  apiBaseUrl: z.string(),
});

export type AppConfig = z.infer<typeof ConfigSchema>;

export async function loadConfig(): Promise<AppConfig> {
  const response = await fetch("/config.json", { cache: "no-store" });
  if (!response.ok) throw new Error(`Configuration could not be loaded (HTTP ${response.status})`);
  return ConfigSchema.parse(await response.json());
}
