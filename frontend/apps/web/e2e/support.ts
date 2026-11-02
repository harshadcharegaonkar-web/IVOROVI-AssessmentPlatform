import { expect, type APIRequestContext, type Page } from "@playwright/test";

export const USERS = {
  orgAdmin: "alice.admin@aurora.test",
  instAdmin: "ines.instadmin@aurora.test",
  student: "asha.student@aurora.test",
  otherTenantAdmin: "bela.admin@borealis.test",
  noAccount: "ghost.user@aurora.test",
} as const;

export function password(): string {
  const p = process.env.DEV_USER_PASSWORD;
  if (!p) throw new Error("DEV_USER_PASSWORD must be set (see deploy/compose/.env)");
  return p;
}

/** Signs in through the real Keycloak login page. */
export async function signIn(page: Page, username: string, path = "/"): Promise<void> {
  await page.goto(path);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password());
  await page.locator("#kc-login").click();
  await expect(page.locator("#username")).toHaveCount(0);
}

/** Access token for API-level test housekeeping (dev realm CLI client, password grant). */
export async function apiToken(request: APIRequestContext, username: string): Promise<string> {
  const keycloak = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
  const res = await request.post(`${keycloak}/realms/ivorovi/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "ivorovi-dev-cli", scope: "openid", username, password: password() },
  });
  expect(res.ok()).toBeTruthy();
  return (await res.json() as { access_token: string }).access_token;
}

/** Deletes organization units whose name starts with {@code prefix}, deepest first (test data created by E2E runs). */
export async function deleteUnitsNamed(request: APIRequestContext, prefix: string): Promise<void> {
  const headers = { Authorization: `Bearer ${await apiToken(request, USERS.orgAdmin)}` };
  const tree = await request.get("/api/v1/org-units/tree", { headers });
  expect(tree.ok()).toBeTruthy();
  type Node = { id: string; parentId?: string; name: string };
  const nodes = (await tree.json()) as Node[];
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const depth = (n: Node): number => (n.parentId && byId.has(n.parentId) ? 1 + depth(byId.get(n.parentId)!) : 0);
  for (const n of nodes.filter((x) => x.name.startsWith(prefix)).sort((a, b) => depth(b) - depth(a))) {
    const detail = await request.get(`/api/v1/org-units/${n.id}`, { headers });
    if (!detail.ok()) continue;
    const version = ((await detail.json()) as { unit: { version: number } }).unit.version;
    const del = await request.delete(`/api/v1/org-units/${n.id}?version=${version}`, { headers });
    expect(del.status(), `delete ${n.name}`).toBe(204);
  }
}
