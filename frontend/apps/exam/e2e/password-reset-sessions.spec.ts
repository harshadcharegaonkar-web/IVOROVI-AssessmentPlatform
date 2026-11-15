import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { DELIVERY, examSetup, mail } from "./support";

/**
 * KI-79 (decision D-16) against the real Keycloak candidate realm: a password reset with several active sessions.
 * - sessions that existed before the reset become unusable: Keycloak refuses their refresh token AND the delivery API refuses
 *   their still-valid access token (the API records the reset, Day 5);
 * - the session the reset flow signed in, and a session created after the reset, keep working;
 * - a second reset ends everything that existed before it, including the first reset's session;
 * - concurrent activity (one session refreshing and calling the API every second throughout) gets no server error.
 * The guard runs every 10 s, so every check polls for up to 45 s; nothing is retried on failure.
 */
const ADMIN = "alice.admin@aurora.test";
const KC = "http://localhost:8180/realms/ivorovi-candidates/protocol/openid-connect/token";

type Session = { access: string; refresh: string };

async function grant(request: APIRequestContext, email: string, secret: string): Promise<Session> {
  const r = await request.post(KC, { form: { grant_type: "password", client_id: "ivorovi-candidate-dev-cli", scope: "openid email profile", username: email, password: secret } });
  expect(r.status(), await r.text()).toBe(200);
  const b = (await r.json()) as { access_token: string; refresh_token: string };
  return { access: b.access_token, refresh: b.refresh_token };
}

async function refresh(request: APIRequestContext, s: Session): Promise<number> {
  const r = await request.post(KC, { form: { grant_type: "refresh_token", client_id: "ivorovi-candidate-dev-cli", refresh_token: s.refresh } });
  if (r.status() === 200) s.refresh = ((await r.json()) as { refresh_token: string }).refresh_token;
  return r.status();
}

async function api(request: APIRequestContext, key: string, s: Session): Promise<number> {
  return (await request.get(`${DELIVERY}/api/v1/delivery/portals/${key}/me`, { headers: { Authorization: `Bearer ${s.access}` } })).status();
}

async function register(page: Page, key: string, email: string, secret: string) {
  await page.goto(`/p/${key}`);
  await page.getByRole("button", { name: "Create an exam account" }).click();
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill("Reset");
  await page.locator("#lastName").fill("Candidate");
  const pw = page.locator("#password");
  if (await pw.count()) {
    await pw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
  }
  await page.locator("input[type=submit], button[type=submit]").first().click();
  const text = await mail(page.request, email, "erif");
  await page.goto(/(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(text)![1]!.replace(/&amp;/g, "&"));
  const proceed = page.getByRole("link", { name: /click here to proceed|back to application/i });
  if (await proceed.isVisible().catch(() => false)) await proceed.click();
  const newPw = page.locator("#password-new");
  if (await newPw.isVisible({ timeout: 5000 }).catch(() => false)) {
    await newPw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
    await page.locator("input[type=submit], button[type=submit]").first().click();
  }
  await expect(page.getByText(email)).toBeVisible();
}

/** The forgotten-password flow in the browser; afterwards the browser holds the reset's own session. */
async function resetPassword(page: Page, key: string, email: string, newSecret: string) {
  const t0 = Date.now();
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.goto(`/p/${key}`);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("link", { name: /forgot password/i }).click();
  await page.locator("#username").fill(email);
  await page.locator("input[type=submit], button[type=submit]").first().click();
  const reset = await mail(page.request, email, "eset", t0);
  await page.goto(/(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(reset)![1]!.replace(/&amp;/g, "&"));
  await page.locator("#password-new").fill(newSecret);
  await page.locator("#password-confirm").fill(newSecret);
  await page.locator("input[type=submit], button[type=submit]").first().click();
  await expect(page.getByText(email)).toBeVisible();
}

test("KI-79: a password reset ends every earlier session at once, keeps later ones, and a second reset ends the first reset's session", async ({ page, request }) => {
  test.setTimeout(300_000);
  const setup = await examSetup(request, ADMIN, "OFF", 1);
  const email = `reset.${Date.now()}@candidates.test`;
  const secret = `Cand-${Date.now()}-pw!`;
  await register(page, setup.key, email, secret);

  // three more sessions (other devices) before the reset; one of them stays busy throughout
  const laptop = await grant(request, email, secret);
  const tablet = await grant(request, email, secret);
  const busy = await grant(request, email, secret);
  expect([await api(request, setup.key, laptop), await api(request, setup.key, tablet)]).toEqual([200, 200]);
  const busyStatuses: number[] = [];
  let stop = false;
  const activity = (async () => {
    while (!stop) {
      busyStatuses.push(await refresh(request, busy), await api(request, setup.key, busy));
      await new Promise((r) => setTimeout(r, 1000));
    }
  })();

  // reset 1 in the browser
  const secret2 = `${secret}-2`;
  await resetPassword(page, setup.key, email, secret2);

  // every pre-reset session becomes unusable: Keycloak refuses the refresh, the delivery API refuses the unexpired token
  await expect(async () => {
    expect(await refresh(request, laptop)).toBe(400);
    expect(await refresh(request, tablet)).toBe(400);
    expect(await api(request, setup.key, laptop)).toBe(401);
    expect(await api(request, setup.key, tablet)).toBe(401);
  }).toPass({ timeout: 45_000, intervals: [2_000] });
  stop = true;
  await activity;
  expect(busyStatuses.every((s) => s < 500)).toBe(true);                     // concurrent activity: no server error
  expect(await api(request, setup.key, busy)).toBe(401);                      // and the busy session ended too

  // the reset's own browser session keeps working (single sign-on without the login form), and so does a new sign-in
  await page.reload();
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(email)).toBeVisible();
  await expect(page.locator("#username")).toHaveCount(0);
  const phone = await grant(request, email, secret2);
  await page.waitForTimeout(15_000);                                          // at least one more guard run
  expect(await refresh(request, phone)).toBe(200);
  expect(await api(request, setup.key, phone)).toBe(200);
  const oldPw = await request.post(KC, { form: { grant_type: "password", client_id: "ivorovi-candidate-dev-cli", username: email, password: secret } });
  expect(oldPw.status()).toBe(400);                                           // Keycloak: invalid_grant for the old password

  // reset 2: everything before it ends, including the phone created after reset 1
  await resetPassword(page, setup.key, email, `${secret}-3`);
  await expect(async () => {
    expect(await refresh(request, phone)).toBe(400);
    expect(await api(request, setup.key, phone)).toBe(401);
  }).toPass({ timeout: 45_000, intervals: [2_000] });
  const after2 = await grant(request, email, `${secret}-3`);
  expect(await api(request, setup.key, after2)).toBe(200);
});
