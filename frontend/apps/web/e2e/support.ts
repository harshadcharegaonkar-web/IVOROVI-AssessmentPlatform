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
export async function signIn(page: Page, username: string, path = "/", secret: string = password()): Promise<void> {
  await page.goto(path);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(secret);
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

/** Latest invitation link emailed to {@code email}, read from Mailpit's API (development mail catcher). */
export async function invitationLink(request: APIRequestContext, email: string): Promise<string> {
  const mailpit = process.env.MAILPIT_URL ?? "http://localhost:8025";
  for (let i = 0; i < 20; i++) {
    const search = await request.get(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
    const messages = ((await search.json()) as { messages?: { ID: string }[] }).messages ?? [];
    if (messages.length > 0) {
      const message = await request.get(`${mailpit}/api/v1/message/${messages[0]!.ID}`);
      const text = ((await message.json()) as { Text: string }).Text;
      const m = /(https?:\/\/\S+\/invitation#token=[A-Za-z0-9_-]{43})/.exec(text);
      if (m) return m[1]!;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No invitation email for ${email}`);
}

/** Revokes every assignment of custom roles whose name starts with {@code prefix}, then archives them. */
export async function archiveRolesNamed(request: APIRequestContext, prefix: string): Promise<void> {
  const headers = { Authorization: `Bearer ${await apiToken(request, USERS.orgAdmin)}` };
  const list = await request.get(`/api/v1/roles?type=custom&size=100&q=${encodeURIComponent(prefix)}`, { headers });
  expect(list.ok()).toBeTruthy();
  type Role = { id: string; name: string };
  for (const role of ((await list.json()) as { items: Role[] }).items.filter((r) => r.name.startsWith(prefix))) {
    const holders = await request.get(`/api/v1/roles/${role.id}/holders?size=100`, { headers });
    for (const h of ((await holders.json()) as { items: { assignmentId: string; userId: string; active: boolean }[] }).items.filter((x) => x.active)) {
      const r = await request.post(`/api/v1/users/${h.userId}/role-assignments/${h.assignmentId}/revoke`, { headers, data: { reason: "E2E cleanup" } });
      expect(r.status(), "revoke").toBe(204);
    }
    const detail = await request.get(`/api/v1/roles/${role.id}`, { headers });
    const version = ((await detail.json()) as { role: { version: number } }).role.version;
    const del = await request.delete(`/api/v1/roles/${role.id}?version=${version}`, { headers });
    expect(del.status(), `archive ${role.name}`).toBe(204);
  }
}
