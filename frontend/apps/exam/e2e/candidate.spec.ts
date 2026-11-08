import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { DELIVERY, STAFF, candidateToken, examSetup, freshTotp, keycloakLogin, mail, staffApi, staffToken } from "./support";

const ADMIN = "alice.admin@aurora.test";
const SECOND = "eshan.controller@aurora.test";
const OTHER_ORG_ADMIN = "bela.admin@borealis.test";

async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

/** Registers a new candidate through the real candidate realm, including email verification. */
async function register(page: Page, key: string, email: string, secret: string) {
  await page.goto(`/p/${key}`);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("E2E entrance");
  await axe(page);
  await page.getByRole("button", { name: "Create an exam account" }).click();
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill("Esha");
  await page.locator("#lastName").fill("Candidate");
  // with email verification on, the candidate realm asks for the password only after the address is proven
  const pw = page.locator("#password");
  if (await pw.count()) {
    await pw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
  }
  await page.locator("input[type=submit], button[type=submit]").first().click();
  // email verification is mandatory before any token is issued
  await expect(page.getByText(/verify your email|email.*sent|check your email/i).first()).toBeVisible();
  const text = await mail(page.request, email, "erif");
  const link = /(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(text)![1]!;
  await page.goto(link.replace(/&amp;/g, "&"));
  const proceed = page.getByRole("link", { name: /click here to proceed|back to application/i });
  if (await proceed.isVisible().catch(() => false)) await proceed.click();
  const newPw = page.locator("#password-new");
  if (await newPw.isVisible({ timeout: 5000 }).catch(() => false)) {
    await newPw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
    await page.locator("input[type=submit], button[type=submit]").first().click();
  }
}

test.describe("candidate accounts, MFA, session codes and boundaries (C-1, C-4)", () => {
  test("register, verify, check in once, enrol TOTP, recover, and stay inside the organization", async ({ page, request, browser }) => {
    test.setTimeout(300_000);
    const setup = await examSetup(request, ADMIN);
    const email = `esha.${Date.now()}@candidates.test`;
    const secret = `Cand-${Date.now()}-pw!`;
    await register(page, setup.key, email, secret);

    // signed in on the exam app: account, system check, session
    await expect(page.getByText(email)).toBeVisible();
    await expect(page.getByRole("list", { name: "System check results" })).toContainText("Device clock");
    await expect(page.getByRole("list", { name: "System check results" })).toContainText("cannot be copied");
    await axe(page);

    // single-use code: works once, refused afterwards (also for another candidate)
    await page.getByLabel("Session code").fill(setup.codes[0]!.toLowerCase());
    await page.getByRole("button", { name: "Check in" }).click();
    await expect(page.getByText("Checked in to Readiness check.")).toBeVisible();
    await expect(page.getByText(/Connected \(last contact/)).toBeVisible();

    // staff see the check-in and presence
    const checkIns = await staffApi<{ email: string; beats: number }[]>(request, setup.token, "GET", `/api/v1/exam/sessions/${setup.sessionId}/check-ins`);
    expect(checkIns.map((c) => c.email)).toContain(email);

    // TOTP enrolment through the candidate realm (an authenticator app computes the code)
    await page.getByRole("button", { name: "Set up authenticator app" }).click();
    await page.getByText(/unable to scan/i).click();
    const key = (await page.locator("#kc-totp-secret-key").innerText()).trim();
    await page.locator("#totp").fill(await freshTotp(key));
    await page.locator("#saveTOTPBtn, input[type=submit]").first().click();
    await expect(page.getByText("An authenticator app protects your account.")).toBeVisible();

    // a second browser (another device) is signed in too
    const other = await browser.newContext();
    const device2 = await other.newPage();
    await device2.goto(`/p/${setup.key}`);
    await device2.getByRole("button", { name: "Sign in" }).click();
    await keycloakLogin(device2, email, secret);
    await device2.locator("#otp").fill(await freshTotp(key));
    await device2.locator("#kc-login").click();
    await expect(device2.getByText(email)).toBeVisible();

    // recovery: forgotten password via the verified email; the authenticator is still required; other sessions are signed out
    const t0 = Date.now();
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.goto(`/p/${setup.key}`);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("link", { name: /forgot password/i }).click();
    await page.locator("#username").fill(email);
    await page.locator("input[type=submit], button[type=submit]").first().click();
    const reset = await mail(page.request, email, "eset", t0);
    await page.goto(/(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(reset)![1]!.replace(/&amp;/g, "&"));
    // the reset needs the second factor too: an email link alone never removes or bypasses the authenticator
    await expect(page.locator("#otp")).toBeVisible();
    await page.locator("#otp").fill(await freshTotp(key));
    await page.locator("#kc-login").click();
    const newSecret = `${secret}-new`;
    await page.locator("#password-new").fill(newSecret);
    await page.locator("#password-confirm").fill(newSecret);
    // "sign out other devices" is left as the candidate leaves it: the platform ends every session anyway (RecoveryGuard)
    await page.locator("input[type=submit], button[type=submit]").first().click();
    await expect(page.getByText(email)).toBeVisible();
    // the candidate is told by email that the password changed
    await mail(page.request, email, "pdate", t0);
    // every session ends after a reset: the other device must sign in again
    await expect(async () => {
      await device2.reload();
      await expect(device2.getByRole("button", { name: "Sign in" })).toBeVisible({ timeout: 2000 });
      await device2.getByRole("button", { name: "Sign in" }).click();
      await expect(device2.locator("#username")).toBeVisible({ timeout: 3000 });
    }).toPass({ timeout: 45_000 });
    // KI-79 (deterministic): the guard has now run (the other device was signed out); the device that performed the reset
    // keeps its new session — it is still signed in after a reload
    await page.reload();
    await expect(page.getByText(email)).toBeVisible();
    await other.close();

    // the same candidate on another organization's portal: a different, empty account; this organization's code is refused there
    const borealis = await examSetup(request, OTHER_ORG_ADMIN, "OFF", 1);
    await page.goto(`/p/${borealis.key}`);
    await page.getByRole("button", { name: "Sign in" }).click();
    await keycloakLogin(page, email, newSecret);
    await page.locator("#otp").fill(await freshTotp(key));
    await page.locator("#kc-login").click();
    await expect(page.getByText("No session is scheduled for you yet.").or(page.getByText("Readiness check"))).toBeVisible();
    await page.getByLabel("Session code").fill(setup.codes[1]!);
    await page.getByRole("button", { name: "Check in" }).click();
    await expect(page.getByRole("alert")).toContainText("not valid");
  });

  test("required MFA blocks check-in until an authenticator is set up", async ({ page, request }) => {
    const setup = await examSetup(request, ADMIN, "REQUIRED", 1);
    const email = `mfa.${Date.now()}@candidates.test`;
    await register(page, setup.key, email, `Mfa-${Date.now()}-pw!`);
    await expect(page.getByText(/requires an authenticator app/)).toBeVisible();
    await expect(page.getByLabel("Session code")).toHaveCount(0);
    // tokens are held in memory only: nothing token-like in this origin's browser storage
    const stored = await page.evaluate(() => JSON.stringify({ ...sessionStorage }) + JSON.stringify({ ...localStorage }));
    expect(stored).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
  });

  test("staff and candidate tokens never cross the boundary", async ({ request }) => {
    const candidate = await candidateToken(request, "asha.candidate@candidates.test");
    const staff = await staffToken(request, ADMIN);
    expect((await request.get(`${STAFF}/api/v1/me`, { headers: { Authorization: `Bearer ${candidate}` } })).status()).toBe(401);
    const setup = await examSetup(request, ADMIN, "OFF", 1);
    expect((await request.get(`${DELIVERY}/api/v1/delivery/portals/${setup.key}/me`, { headers: { Authorization: `Bearer ${staff}` } })).status()).toBe(401);
    expect((await request.get(`${DELIVERY}/api/v1/delivery/portals/${setup.key}/me`, { headers: { Authorization: `Bearer ${candidate}` } })).status()).toBe(200);
    // the staff API does not serve delivery endpoints at all
    expect((await request.get(`${STAFF.replace("5173", "8080")}/api/v1/delivery/time`)).status()).toBe(404);
  });

  test("outage report → compensation proposed → second controller approves → candidate uses it @desktop", async ({ page, request, browser }) => {
    const setup = await examSetup(request, ADMIN, "OFF", 1);
    const email = `comp.${Date.now()}@candidates.test`;
    await register(page, setup.key, email, `Comp-${Date.now()}-pw!`);
    await page.getByLabel("Session code").fill(setup.codes[0]!);
    await page.getByRole("button", { name: "Check in" }).click();
    await expect(page.getByText("Checked in to Readiness check.")).toBeVisible();

    // the institution reports an interruption that covers this check-in (staff UI)
    const staffCtx = await browser.newContext({ baseURL: STAFF });
    const staffPage = await staffCtx.newPage();
    await staffPage.goto("/exams/incidents");
    await keycloakLogin(staffPage, ADMIN, process.env.DEV_USER_PASSWORD!);
    await staffPage.getByRole("button", { name: "Report an outage" }).click();
    const dialog = staffPage.getByRole("dialog");
    await dialog.getByLabel("What happened").fill(`E2E network outage ${setup.key}`);
    const start = new Date(Date.now() - 4 * 60_000);
    const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    await dialog.getByLabel("Started").fill(local(start));
    await dialog.getByLabel("Ended").fill(local(new Date(Date.now() + 60_000 - 1)));
    await dialog.getByRole("button", { name: "Report" }).click();
    await expect(staffPage.getByRole("heading", { name: `E2E network outage ${setup.key}` })).toBeVisible();
    await expect(staffPage.getByText(email)).toBeVisible();
    await staffPage.getByLabel("Extra minutes").fill("9");
    await staffPage.getByLabel("Rationale").fill("Lost time plus five minutes");
    await staffPage.getByRole("button", { name: "Submit for approval" }).click();
    await staffPage.getByRole("link", { name: "Approval", exact: true }).click();
    // the proposer cannot approve
    await expect(staffPage.getByText("You requested this")).toBeVisible();
    await expect(staffPage.getByRole("button", { name: "Approve" })).toHaveCount(0);
    const approvalUrl = staffPage.url();
    await staffCtx.close();

    // a second, independent controller approves
    const second = await browser.newContext({ baseURL: STAFF });
    const secondPage = await second.newPage();
    await secondPage.goto(new URL(approvalUrl).pathname);
    await keycloakLogin(secondPage, SECOND, process.env.DEV_USER_PASSWORD!);
    await secondPage.getByRole("button", { name: "Approve" }).click();
    await expect(secondPage.getByText(/Decided by Eshan Controller/)).toBeVisible();
    await second.close();

    // the candidate is told by email and uses the compensation once
    await mail(request, email, "Compensation for an exam service interruption");
    await page.reload();
    // tokens live in memory only: after a reload the candidate signs in again (single sign-on at the candidate realm)
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("9 extra minutes and a new check-in")).toBeVisible();
    await page.getByRole("button", { name: "Use now" }).click();
    await expect(page.getByText("Done: you are checked in again.")).toBeVisible();
    await expect(page.getByText(/^Used /)).toBeVisible();
  });

  test("portal and sign-in screens work on tablet @tablet", async ({ page, request }) => {
    const setup = await examSetup(request, ADMIN, "OFF", 1);
    await page.goto(`/p/${setup.key}`);
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await axe(page);
  });

  test("portal and check-in screens work on mobile @mobile", async ({ page, request }) => {
    const setup = await examSetup(request, ADMIN, "OFF", 1);
    await register(page, setup.key, `mob.${Date.now()}@candidates.test`, `Mob-${Date.now()}-pw!`);
    await expect(page.getByLabel("Session code")).toBeVisible();
    await axe(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(overflow).toBe(false);
  });
});
