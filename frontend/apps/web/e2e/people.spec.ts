import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { invitationLink, signIn, USERS } from "./support";

const INES_ID = "0192a000-0000-7000-8000-0000000a1004";
const ARUN_ID = "0192a000-0000-7000-8000-0000000a1002";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help}`);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;

async function createUser(page: Page, given: string, email: string, unitLabel: RegExp | null, invite = true) {
  await page.goto("/people/users");
  await page.getByRole("button", { name: "New user" }).first().click();
  const dialog = page.getByRole("dialog", { name: "New user" });
  await dialog.getByLabel("First name").fill(given);
  await dialog.getByLabel("Last name").fill("E2E");
  await dialog.getByLabel("Email", { exact: true }).fill(email);
  if (unitLabel) {
    const select = dialog.getByLabel("Belongs to");
    const value = await select.locator("option").filter({ hasText: unitLabel }).first().getAttribute("value");
    await select.selectOption(value!);
  }
  if (!invite) await dialog.getByLabel("Send invitation email now").uncheck();
  await dialog.getByRole("button", { name: "Create user" }).click();
  await expect(page.getByRole("heading", { name: `${given} E2E`, level: 1 })).toBeVisible();
}

test.describe("Increment 1.2: users and staff", () => {
  test("user list, filters and the access overview", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/people/users");
    const table = page.getByRole("table", { name: "Users" });
    await expect(table.getByRole("link", { name: "Ines InstAdmin" })).toBeVisible();
    await page.getByLabel("Status").selectOption("SUSPENDED");
    await expect(page).toHaveURL(/status=SUSPENDED/);
    await page.getByLabel("Status").selectOption("");
    await page.getByRole("searchbox", { name: "Search users" }).fill("ines");
    await expect(table.getByRole("row")).toHaveCount(2);
    expect(await seriousViolations(page)).toEqual([]);

    await table.getByRole("link", { name: "Ines InstAdmin" }).click();
    await expect(page.getByRole("heading", { name: "Ines InstAdmin", level: 1 })).toBeVisible();
    await page.getByRole("tab", { name: "Organization" }).click();
    await expect(page.getByRole("table", { name: "Organizational assignments" })).toContainText("Aurora Institute of Technology");
    await page.getByRole("tab", { name: /Roles/ }).click();
    await expect(page.getByRole("table", { name: "Role assignments" })).toContainText("Institution Administrator");
    await page.getByRole("tab", { name: "Access" }).click();
    const perms = page.getByTestId("effective-permissions");
    await expect(perms).toContainText("user.manage");
    await expect(perms).toContainText("Aurora Institute of Technology");
    await expect(perms).not.toContainText("tenant.settings.manage");
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("invite → accept on the public page → sign in → roles → suspend → deactivate", async ({ page, browser, request, baseURL }) => {
    const id = stamp();
    const email = `e2e.${id}@aurora.test`;
    const secret = `E2e-${id}-Passw0rd!`;
    await signIn(page, USERS.orgAdmin);
    await createUser(page, `Invitee${id}`, email, /Aurora Institute of Technology/);
    await expect(page.getByTestId("invitation-card")).toContainText("Pending");
    const userUrl = page.url();

    // the invitee opens the emailed link in a fresh browser (no session)
    const link = new URL(await invitationLink(request, email));
    const invitee = await browser.newContext({ baseURL });
    const ip = await invitee.newPage();
    await ip.goto(`/invitation${link.hash}`);
    await expect(ip.getByRole("heading", { name: `Welcome, Invitee${id}` })).toBeVisible();
    await expect(ip.getByTestId("invitation-email")).toHaveText(email);
    await expect(ip).not.toHaveURL(/token=/);                                    // removed from the address bar
    expect(await seriousViolations(ip)).toEqual([]);
    await ip.getByLabel("Choose a password").fill("short");
    await ip.getByLabel("Confirm password").fill("short");
    await ip.getByRole("button", { name: "Activate account" }).click();
    await expect(ip.getByText("Use at least 12 characters")).toBeVisible();
    await ip.getByLabel("Choose a password").fill(secret);
    await ip.getByLabel("Confirm password").fill(secret);
    await ip.getByRole("button", { name: "Activate account" }).click();
    await expect(ip.getByRole("heading", { name: "Your account is ready" })).toBeVisible();
    // the same link cannot be used again
    await ip.goto("about:blank");
    await ip.goto(`/invitation${link.hash}`);
    await expect(ip.getByRole("heading", { name: "Invitation not valid" })).toBeVisible();
    // and the new user signs in through Keycloak with the chosen password
    await signIn(ip, email, "/", secret);
    await expect(ip.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
    await expect(ip.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Users" })).toHaveCount(0);

    // the administrator assigns and revokes a role, then suspends and deactivates the account
    await page.goto(userUrl);
    await expect(page.getByRole("main").getByText("Active", { exact: true }).first()).toBeVisible();
    await page.getByRole("tab", { name: /Roles/ }).click();
    await page.getByRole("button", { name: "Assign role" }).click();
    let dialog = page.getByRole("dialog", { name: "Assign role" });
    await dialog.getByLabel("Role").selectOption({ label: "Faculty" });
    const scope = dialog.getByLabel("Scope");
    await scope.selectOption((await scope.locator("option").filter({ hasText: /Aurora Institute of Technology/ }).first().getAttribute("value"))!);
    await dialog.getByRole("button", { name: "Assign" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Role assigned" })).toBeVisible();
    await page.getByRole("tab", { name: "Access" }).click();
    await expect(page.getByTestId("effective-permissions")).toContainText("org_unit.read");
    await page.getByRole("tab", { name: /Roles/ }).click();
    await page.getByRole("button", { name: /Revoke Faculty/ }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Revoke" }).click();
    await expect(dialog.getByText("Give a reason")).toBeVisible();
    await dialog.getByLabel("Reason (required)").fill("E2E: assignment ended");
    await dialog.getByRole("button", { name: "Revoke" }).click();
    await expect(page.getByRole("table", { name: "Role assignments" })).toContainText("Revoked");

    await page.getByRole("button", { name: "Account actions" }).click();
    await page.getByRole("menuitem", { name: "Suspend…" }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Reason/).fill("E2E suspension");
    await dialog.getByRole("button", { name: "Suspend" }).click();
    await expect(page.getByText("Reason: E2E suspension")).toBeVisible();
    // the suspended user's sessions were ended, and signing in again is refused by Keycloak
    await ip.reload();
    await ip.locator("#username").fill(email);
    await ip.locator("#password").fill(secret);
    await ip.locator("#kc-login").click();
    await expect(ip.getByText(/account is disabled/i)).toBeVisible();
    await invitee.close();

    await page.getByRole("button", { name: "Account actions" }).click();
    await page.getByRole("menuitem", { name: "Deactivate…" }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Reason/).fill("E2E cleanup");
    await dialog.getByRole("button", { name: "Deactivate" }).click();
    await expect(page.getByRole("main").getByText("Deactivated").first()).toBeVisible();
  });

  test("invitations page: resend replaces the link, revoke stops it", async ({ page, request, browser, baseURL }) => {
    const id = stamp();
    const email = `e2e.inv.${id}@aurora.test`;
    await signIn(page, USERS.orgAdmin);
    await createUser(page, `Pending${id}`, email, /Aurora College of Commerce/);
    const first = new URL(await invitationLink(request, email));
    await page.goto("/people/invitations");
    await page.getByRole("searchbox", { name: "Search invitations" }).fill(email);
    const table = page.getByRole("table", { name: "Invitations" });
    await expect(table.getByRole("row")).toHaveCount(2);
    expect(await seriousViolations(page)).toEqual([]);
    await table.getByRole("button", { name: `Actions for ${email}` }).click();
    await page.getByRole("menuitem", { name: "Resend" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Invitation sent" })).toBeVisible();
    await expect(table).toContainText("2×");
    await table.getByRole("button", { name: `Actions for ${email}` }).click();
    await page.getByRole("menuitem", { name: "Revoke…" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Revoke" }).click();
    await expect(table).toContainText("Revoked");

    const ctx = await browser.newContext({ baseURL });
    const anon = await ctx.newPage();
    await anon.goto(`/invitation${first.hash}`);                                  // replaced by the resend, and revoked
    await expect(anon.getByRole("heading", { name: "Invitation not valid" })).toBeVisible();
    await ctx.close();
  });

  test("staff profile without a login, assignments, then link to a user account", async ({ page }) => {
    const id = stamp();
    await signIn(page, USERS.orgAdmin, "/people/staff");
    await page.getByRole("button", { name: "New staff profile" }).first().click();
    const dialog = page.getByRole("dialog", { name: "New staff profile" });
    await dialog.getByLabel("Staff ID").fill(`E2E-${id}`);
    await dialog.getByLabel("Category").selectOption("FACULTY");
    await dialog.getByLabel("First name").fill(`Staff${id}`);
    await dialog.getByLabel("Last name").fill("E2E");
    await dialog.getByLabel("Designation").fill("Assistant Professor");
    const unit = dialog.getByLabel("Department, campus or college");
    await unit.selectOption((await unit.locator("option").filter({ hasText: /Computer Science/ }).first().getAttribute("value"))!);
    await dialog.getByRole("button", { name: "Create profile" }).click();
    await expect(page.getByRole("heading", { name: `Staff${id} E2E`, level: 1 })).toBeVisible();
    await expect(page.getByText("This person has no login.")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    const staffUrl = page.url();

    // duplicate staff ID is refused
    await page.goto("/people/staff");
    await page.getByRole("button", { name: "New staff profile" }).first().click();
    const dup = page.getByRole("dialog", { name: "New staff profile" });
    await dup.getByLabel("Staff ID").fill(`e2e-${id}`);
    await dup.getByLabel("Category").selectOption("PROCTOR");
    await dup.getByLabel("First name").fill("Dup");
    await dup.getByRole("button", { name: "Create profile" }).click();
    await expect(dup.getByText("Another staff profile already uses this staff ID.")).toBeVisible();
    await page.keyboard.press("Escape");

    // a second organizational assignment
    await page.goto(staffUrl);
    await page.getByRole("tab", { name: /Organization/ }).click();
    await page.getByRole("button", { name: "Add" }).click();
    const add = page.getByRole("dialog", { name: "Add organizational assignment" });
    const sel = add.getByLabel("Unit");
    await sel.selectOption((await sel.locator("option").filter({ hasText: /Department of Commerce/ }).first().getAttribute("value"))!);
    await add.getByLabel("Title (optional)").fill("Visiting faculty");
    await add.getByRole("button", { name: "Add assignment" }).click();
    const table = page.getByRole("table", { name: "Organizational assignments" });
    await expect(table).toContainText("Department of Commerce");
    await expect(table).toContainText("Visiting faculty");

    // create a user (no invitation yet) and link it
    const email = `e2e.staff.${id}@aurora.test`;
    await createUser(page, `Linked${id}`, email, /Computer Science/, false);
    await page.goto(staffUrl);
    await page.getByRole("button", { name: "Link user account" }).click();
    const link = page.getByRole("dialog", { name: "Link to user account" });
    await link.getByLabel("Find user").fill(email);
    await link.getByRole("radio").first().check();
    await link.getByRole("button", { name: "Link account" }).click();
    await expect(page.getByRole("link", { name: email })).toBeVisible();
    await page.getByRole("link", { name: email }).click();
    await expect(page.getByRole("link", { name: new RegExp(`E2E-${id}`) })).toBeVisible();
  });

  test("scoped administrator: own subtree only, and no privilege escalation", async ({ page }) => {
    await signIn(page, USERS.instAdmin, "/people/users");
    const table = page.getByRole("table", { name: "Users" });
    await expect(table.getByRole("link", { name: "Arun Faculty" })).toBeVisible();
    await expect(table.getByRole("link", { name: "Alice Admin" })).toHaveCount(0);   // organization-level user
    await page.goto(`/people/users/${ARUN_ID}?tab=roles`);
    await page.getByRole("button", { name: "Assign role" }).click();
    const dialog = page.getByRole("dialog", { name: "Assign role" });
    await dialog.getByLabel("Role").selectOption({ label: "Organization Administrator" });
    await expect(dialog.getByLabel("Scope").locator("option", { hasText: "Whole organization" })).toHaveCount(0);
    await dialog.getByLabel("Scope").selectOption({ index: 1 });
    await dialog.getByRole("button", { name: "Assign" }).click();
    await expect(dialog.getByText(/You can only manage roles whose permissions you hold yourself/)).toBeVisible();
    await page.keyboard.press("Escape");
    // own account: no self-service role or status changes
    await page.goto(`/people/users/${INES_ID}?tab=roles`);
    await expect(page.getByRole("button", { name: "Assign role" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Account actions" })).toHaveCount(0);
  });

  test("an unknown invitation link is refused", async ({ page }) => {
    await page.goto(`/invitation#token=${"A".repeat(43)}`);
    await expect(page.getByRole("heading", { name: "Invitation not valid" })).toBeVisible();
    await expect(page.locator("#username")).toHaveCount(0);                      // no sign-in redirect
  });

  for (const path of ["/people/users", "/people/staff", "/people/invitations", `/people/users/${INES_ID}`, `/people/users/${INES_ID}?tab=access`]) {
    for (const tag of ["@mobile", "@tablet"]) {
      test(`no horizontal scrolling on ${path} ${tag}`, async ({ page }) => {
        await signIn(page, USERS.orgAdmin, path);
        await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
        await page.waitForLoadState("networkidle");
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow).toBeLessThanOrEqual(0);
      });
    }
  }

  test("public invitation page has no horizontal scrolling @mobile", async ({ page }) => {
    await page.goto(`/invitation#token=${"A".repeat(43)}`);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  });
});
