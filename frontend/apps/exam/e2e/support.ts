import { createHmac } from "node:crypto";
import { expect, type APIRequestContext, type Page } from "@playwright/test";

export const STAFF = process.env.E2E_STAFF_URL ?? "http://localhost:5173";
export const DELIVERY = process.env.DELIVERY_URL ?? "http://localhost:8090";
const KEYCLOAK = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
const MAILPIT = process.env.MAILPIT_URL ?? "http://localhost:8025";

export function password(): string {
  const p = process.env.DEV_USER_PASSWORD;
  if (!p) throw new Error("DEV_USER_PASSWORD must be set (see deploy/compose/.env)");
  return p;
}

/** Staff token (dev realm CLI client) for test housekeeping through the staff API. */
export async function staffToken(request: APIRequestContext, username: string): Promise<string> {
  const r = await request.post(`${KEYCLOAK}/realms/ivorovi/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "ivorovi-dev-cli", scope: "openid", username, password: password() },
  });
  expect(r.ok()).toBeTruthy();
  return ((await r.json()) as { access_token: string }).access_token;
}

/** Candidate token (candidate realm dev CLI client). */
export async function candidateToken(request: APIRequestContext, username: string, secret = password()): Promise<string> {
  const r = await request.post(`${KEYCLOAK}/realms/ivorovi-candidates/protocol/openid-connect/token`, {
    form: { grant_type: "password", client_id: "ivorovi-candidate-dev-cli", scope: "openid email profile", username, password: secret },
  });
  expect(r.ok(), await r.text()).toBeTruthy();
  return ((await r.json()) as { access_token: string }).access_token;
}

export async function staffApi<T>(request: APIRequestContext, token: string, method: "GET" | "POST" | "PUT", path: string, data?: unknown): Promise<T> {
  const r = await request.fetch(`${STAFF}${path}`, { method, headers: { Authorization: `Bearer ${token}` }, ...(data === undefined ? {} : { data }) });
  expect(r.ok(), `${method} ${path}: ${r.status()} ${await r.text()}`).toBeTruthy();
  return (r.status() === 204 ? undefined : await r.json()) as T;
}

/** A fresh portal with one open readiness session and single-use codes, created through the real staff API. */
export async function examSetup(request: APIRequestContext, admin: string, mfaPolicy = "OPTIONAL", codes = 3) {
  const token = await staffToken(request, admin);
  const key = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  const portal = await staffApi<{ id: string }>(request, token, "POST", "/api/v1/exam/portals", { portalKey: key, name: `E2E entrance ${key}`, mfaPolicy, selfRegistration: true });
  const now = Date.now();
  const session = await staffApi<{ id: string }>(request, token, "POST", "/api/v1/exam/sessions",
    { portalId: portal.id, name: "Readiness check", startsAt: new Date(now - 5 * 60_000).toISOString(), endsAt: new Date(now + 60 * 60_000).toISOString() });
  const issued = await staffApi<{ code: string }[]>(request, token, "POST", `/api/v1/exam/sessions/${session.id}/codes`, { count: codes });
  return { key, portalId: portal.id, sessionId: session.id, codes: issued.map((c) => c.code), token };
}

/** Latest email to {@code to} whose subject contains {@code subject}; returns its text. */
export async function mail(request: APIRequestContext, to: string, subject: string, after = 0): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const s = await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${to}`)}`);
    const list = ((await s.json()) as { messages?: { ID: string; Subject: string; Created: string }[] }).messages ?? [];
    const m = list.find((x) => x.Subject.includes(subject) && Date.parse(x.Created) >= after);
    if (m) return ((await (await request.get(`${MAILPIT}/api/v1/message/${m.ID}`)).json()) as { Text: string }).Text;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No email "${subject}" to ${to}`);
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), as an authenticator app computes it. */
export function totp(base32: string, at = Date.now()): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = base32.replace(/[\s=]/g, "").toUpperCase();
  let bits = "";
  for (const c of clean) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1]! & 0xf;
  const n = ((h[o]! & 0x7f) << 24) | (h[o + 1]! << 16) | (h[o + 2]! << 8) | h[o + 3]!;
  return String(n % 1_000_000).padStart(6, "0");
}

let lastWindow = 0;
/** A TOTP code that has not been used yet (Keycloak refuses re-use within a window): waits for the next 30 s window if needed. */
export async function freshTotp(base32: string): Promise<string> {
  let w = Math.floor(Date.now() / 30_000);
  if (w <= lastWindow) {
    await new Promise((r) => setTimeout(r, (lastWindow + 1) * 30_000 - Date.now() + 500));
    w = Math.floor(Date.now() / 30_000);
  }
  lastWindow = w;
  return totp(base32);
}

/** Keycloak login form (candidate or staff realm). */
export async function keycloakLogin(page: Page, username: string, secret: string) {
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(secret);
  await page.locator("#kc-login").click();
}
