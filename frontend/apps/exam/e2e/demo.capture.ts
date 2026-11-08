// Demonstration screenshots (not part of the test suite: file name does not match *.spec.ts).
// Run: DEMO_OUT=<dir> npx playwright test -c e2e/playwright.config.ts --testMatch 'demo.capture.ts' --project=desktop
import { expect, test, type Page } from "@playwright/test";
import { STAFF, examSetup, keycloakLogin, mail, staffApi } from "./support";

const OUT = process.env.DEMO_OUT ?? "demo";
const shot = (page: Page, name: string) => page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });

async function register(page: Page, key: string, email: string, secret: string) {
  await page.goto(`/p/${key}`);
  await shot(page, "01-exam-portal-landing");
  await page.getByRole("button", { name: "Create an exam account" }).click();
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill("Meera");
  await page.locator("#lastName").fill("Candidate");
  await shot(page, "02-candidate-registration");
  await page.locator("input[type=submit], button[type=submit]").first().click();
  const text = await mail(page.request, email, "erif");
  await page.goto(/(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(text)![1]!.replace(/&amp;/g, "&"));
  const proceed = page.getByRole("link", { name: /click here to proceed|back to application/i });
  if (await proceed.isVisible().catch(() => false)) await proceed.click();
  await page.locator("#password-new").fill(secret);
  await page.locator("#password-confirm").fill(secret);
  await page.locator("input[type=submit], button[type=submit]").first().click();
}

test("Increment 2.0 demonstration", async ({ page, request, browser }) => {
  test.setTimeout(300_000);
  const setup = await examSetup(request, "alice.admin@aurora.test", "OPTIONAL", 5);
  const email = `meera.${Date.now()}@candidates.test`;
  await register(page, setup.key, email, `Demo-${Date.now()}-pw!`);
  await expect(page.getByRole("list", { name: "System check results" })).toContainText("Device key");
  await shot(page, "03-candidate-home-system-check");
  await page.getByLabel("Session code").fill(setup.codes[0]!);
  await page.getByRole("button", { name: "Check in" }).click();
  await expect(page.getByText(/Connected \(last contact/)).toBeVisible();
  await shot(page, "04-candidate-checked-in-presence");
  await page.getByLabel("Session code").count();

  const staff = await browser.newContext({ baseURL: STAFF, viewport: { width: 1440, height: 900 } });
  const s = await staff.newPage();
  await s.goto(`/exams/sessions/${setup.sessionId}`);
  await keycloakLogin(s, "alice.admin@aurora.test", process.env.DEV_USER_PASSWORD!);
  await expect(s.getByText(email)).toBeVisible();
  await s.getByLabel("Number of codes").fill("4");
  await s.getByRole("button", { name: "Generate" }).click();
  await expect(s.getByTestId("issued-codes")).toBeVisible();
  await shot(s, "05-staff-session-codes-once-and-check-ins");
  await s.goto("/exams/portals");
  await expect(s.getByRole("table", { name: "Exam portals" })).toBeVisible();
  await shot(s, "06-staff-exam-portals");
  await s.goto("/exams/incidents");
  await s.getByRole("button", { name: "Report an outage" }).click();
  const d = s.getByRole("dialog");
  const local = (x: Date) => new Date(x.getTime() - x.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  await d.getByLabel("Started").fill(local(new Date(Date.now() - 4 * 60_000)));
  await d.getByLabel("Ended").fill(local(new Date(Date.now() + 59_000)));
  await d.getByLabel("What happened").fill(`Campus network interruption (${setup.key})`);
  await d.getByRole("button", { name: "Report" }).click();
  await expect(s.getByText(email)).toBeVisible();
  await s.getByLabel("Extra minutes").fill("9");
  await s.getByLabel("Rationale").fill("Four minutes lost plus five minutes");
  await shot(s, "07-staff-incident-propose-compensation");
  await s.getByRole("button", { name: "Submit for approval" }).click();
  await s.getByRole("link", { name: "Approval", exact: true }).click();
  await expect(s.getByText("You requested this")).toBeVisible();
  await shot(s, "08-four-eyes-requester-cannot-approve");
  const url = new URL(s.url()).pathname;
  await staff.close();

  const second = await browser.newContext({ baseURL: STAFF, viewport: { width: 1440, height: 900 } });
  const e = await second.newPage();
  await e.goto("/approvals");
  await keycloakLogin(e, "eshan.controller@aurora.test", process.env.DEV_USER_PASSWORD!);
  await expect(e.getByRole("table")).toBeVisible();
  await shot(e, "09-approvals-inbox-second-controller");
  await e.goto(url);
  await e.getByRole("button", { name: "Approve" }).click();
  await expect(e.getByText(/Decided by Eshan Controller/)).toBeVisible();
  await shot(e, "10-approved-by-second-controller");
  await second.close();

  await mail(request, email, "Compensation for an exam service interruption");
  await page.reload();
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("9 extra minutes and a new check-in")).toBeVisible();
  await shot(page, "11-candidate-compensation-available");
  await page.getByRole("button", { name: "Use now" }).click();
  await expect(page.getByText("Done: you are checked in again.")).toBeVisible();
  await shot(page, "12-candidate-compensation-used");
  void staffApi;
});
