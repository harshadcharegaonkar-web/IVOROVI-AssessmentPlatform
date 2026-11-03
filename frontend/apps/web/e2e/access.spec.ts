import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { archiveRolesNamed, signIn, USERS } from "./support";

const ORG_ADMIN_ROLE = "01920000-0000-7000-8000-000000000002";
const INSTITUTION_ADMIN_ROLE = "01920000-0000-7000-8000-000000000003";
const ARUN_ID = "0192a000-0000-7000-8000-0000000a1002";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help}`);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;

async function pickPermissions(page: Page, names: string[]) {
  const configurator = page.getByTestId("permission-configurator");
  for (const n of names) await configurator.getByRole("checkbox", { name: new RegExp(`^${n}\\b`) }).check();
}

test.describe("Increment 1.3: custom roles and permissions", () => {
  test.beforeAll(async ({ request }) => { await archiveRolesNamed(request, "E2E "); });
  test.afterAll(async ({ request }) => { await archiveRolesNamed(request, "E2E "); });

  test("role list, filters and the permission catalogue", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/access/roles");
    const table = page.getByRole("table", { name: "Roles" });
    await expect(table.getByRole("link", { name: "Institution Administrator" })).toBeVisible();
    await expect(table.getByRole("link", { name: "Platform Super Administrator" })).toHaveCount(0);
    await page.getByLabel("Type").selectOption("system");
    await expect(page).toHaveURL(/type=system/);
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Permission Catalogue" }).click();
    await expect(page.getByRole("heading", { name: "Permission catalogue", level: 1 })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Student management" })).toBeVisible();
    await expect(page.getByText("Coming later").first()).toBeVisible();
    await page.getByRole("searchbox", { name: "Filter permissions" }).fill("audit");
    await expect(page.getByRole("table", { name: "Audit and administration permissions" })).toContainText("audit.read");
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("create with the configurator, edit, clone and compare", async ({ page }) => {
    const id = stamp();
    await signIn(page, USERS.orgAdmin, "/access/roles/new");
    await expect(page.getByRole("heading", { name: "New role", level: 1 })).toBeVisible();
    await page.getByLabel("Name", { exact: true }).fill(`E2E Reviewer ${id}`);
    await page.getByLabel("Description").fill("Reviews user and staff records");
    // planned permissions are visible but cannot be chosen
    await page.getByTestId("planned-permissions").locator("summary").click();
    await expect(page.getByTestId("permission-configurator").getByRole("checkbox", { name: /^View admissions/ })).toBeDisabled();
    await pickPermissions(page, ["View users", "View staff"]);
    const summary = page.getByTestId("effective-access-summary");
    await expect(summary).toContainText("2 permission(s) selected");
    await expect(summary).toContainText("View users");
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("button", { name: "Create role" }).click();
    await expect(page.getByRole("heading", { name: `E2E Reviewer ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByTestId("role-permissions")).toContainText("View staff");
    expect(await seriousViolations(page)).toEqual([]);
    const roleUrl = page.url();

    await page.getByRole("link", { name: "Edit" }).click();
    await pickPermissions(page, ["View roles"]);
    await expect(page.getByTestId("effective-access-summary")).toContainText("Added: role.read");
    await page.getByRole("button", { name: "Save role" }).click();
    await expect(page.getByTestId("role-permissions")).toContainText("View roles");

    await page.getByRole("link", { name: "Clone" }).click();
    await expect(page.getByLabel("Name", { exact: true })).toHaveValue(`E2E Reviewer ${id} (copy)`);
    await page.getByLabel("Name", { exact: true }).fill(`E2E Reviewer copy ${id}`);
    await page.getByTestId("permission-configurator").getByRole("checkbox", { name: /^View staff/ }).uncheck();
    await page.getByRole("button", { name: "Create clone" }).click();
    await expect(page.getByRole("heading", { name: `E2E Reviewer copy ${id}`, level: 1 })).toBeVisible();
    await page.getByRole("tab", { name: "Overview" }).click();
    await expect(page.getByRole("tabpanel").getByText(`E2E Reviewer ${id}`, { exact: true })).toBeVisible();      // cloned from

    const a = roleUrl.split("/").pop()!;
    const b = page.url().split("/").pop()!.split("?")[0]!;
    await page.goto(`/access/roles/compare?ids=${a},${b}`);
    const matrix = page.getByTestId("role-comparison");
    await expect(matrix).toContainText("View staff");
    await page.getByLabel("Show only differences").check();
    await expect(matrix.getByRole("row", { name: /View users/ })).toHaveCount(0);
    await expect(matrix.getByRole("row", { name: /View staff/ })).toHaveCount(1);
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("assign a custom role, deactivate it (access ends), reactivate, then archive", async ({ page, browser, baseURL }) => {
    const id = stamp();
    await signIn(page, USERS.orgAdmin, "/access/roles/new");
    await page.getByLabel("Name", { exact: true }).fill(`E2E Viewer ${id}`);
    await pickPermissions(page, ["View users"]);
    await page.getByRole("button", { name: "Create role" }).click();
    await expect(page.getByRole("heading", { name: `E2E Viewer ${id}`, level: 1 })).toBeVisible();
    const roleUrl = page.url();

    await page.goto(`/people/users/${ARUN_ID}?tab=roles`);
    await page.getByRole("button", { name: "Assign role" }).click();
    const dialog = page.getByRole("dialog", { name: "Assign role" });
    await dialog.getByLabel("Role").selectOption({ label: `E2E Viewer ${id}` });
    const scope = dialog.getByLabel("Scope");
    await scope.selectOption((await scope.locator("option").filter({ hasText: /Aurora Institute of Technology/ }).first().getAttribute("value"))!);
    await dialog.getByRole("button", { name: "Assign" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Role assigned" })).toBeVisible();

    const ctx = await browser.newContext({ baseURL });
    const arun = await ctx.newPage();
    await signIn(arun, "arun.faculty@aurora.test", "/people/users");
    await expect(arun.getByRole("heading", { name: "Users", level: 1 })).toBeVisible();

    await page.goto(roleUrl);
    await page.getByRole("tab", { name: /Assigned users/ }).click();
    await expect(page.getByRole("table", { name: "Assigned users" })).toContainText("Arun Faculty");
    await page.getByRole("button", { name: "Role actions" }).click();
    await page.getByRole("menuitem", { name: "Deactivate…" }).click();
    let confirm = page.getByRole("dialog");
    await expect(confirm).toContainText("1 assignment(s)");
    await confirm.getByLabel("Reason (required)").fill("E2E check");
    await confirm.getByRole("button", { name: "Deactivate" }).click();
    await expect(page.getByText("This role is deactivated")).toBeVisible();

    await arun.goto("/people/users");                                             // no stale permissions
    await expect(arun.getByText("Access denied")).toBeVisible();

    await page.getByRole("button", { name: "Role actions" }).click();
    await page.getByRole("menuitem", { name: "Activate…" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Activate" }).click();
    await expect(page.getByText("This role is deactivated")).toHaveCount(0);
    await arun.goto("/people/users");
    await expect(arun.getByRole("heading", { name: "Users", level: 1 })).toBeVisible();
    await ctx.close();

    // archive is refused while assigned
    await page.getByRole("button", { name: "Role actions" }).click();
    await page.getByRole("menuitem", { name: "Archive…" }).click();
    confirm = page.getByRole("dialog");
    await expect(confirm).toContainText("still assigned");
  });

  test("a college administrator: unit-limited roles only, within their ceiling; system roles are read-only", async ({ page }) => {
    const id = stamp();
    await signIn(page, USERS.instAdmin, "/access/roles/new");
    const scope = page.getByLabel("Where can this role be used?");
    await expect(scope.locator("option", { hasText: "Whole organization" })).toHaveCount(0);
    await expect(page.getByTestId("permission-configurator").getByRole("checkbox", { name: /^View the audit trail/ })).toBeDisabled();
    await expect(page.getByTestId("permission-configurator").getByRole("checkbox", { name: /^Change organization settings/ })).toBeDisabled();
    await page.getByLabel("Name", { exact: true }).fill(`E2E Lab Assistant ${id}`);
    await pickPermissions(page, ["View the organization hierarchy", "View staff"]);
    await page.getByRole("button", { name: "Create role" }).click();
    await expect(page.getByRole("heading", { name: `E2E Lab Assistant ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByText(/Usable only within/)).toContainText("Aurora Institute of Technology");

    await page.goto(`/access/roles/${ORG_ADMIN_ROLE}`);
    await expect(page.getByText("System roles are defined by Ivorovi and cannot be changed.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Edit" })).toHaveCount(0);
    await page.goto(`/access/roles/${ORG_ADMIN_ROLE}/edit`);
    await expect(page.getByText("This role cannot be edited")).toBeVisible();
    // cloning the organization administrator into the college drops what the college admin cannot grant
    await page.goto(`/access/roles/new?from=${ORG_ADMIN_ROLE}`);
    await expect(page.getByText(/permission\(s\) of Organization Administrator were left out/)).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("roles can be limited to a university or a campus from the editor", async ({ page }) => {
    const id = stamp();
    await signIn(page, USERS.orgAdmin);
    for (const [label, unit] of [["University", /Aurora University/], ["Campus", /AIT North Campus/]] as const) {
      await page.goto("/access/roles/new");
      await page.getByLabel("Name", { exact: true }).fill(`E2E ${label} scoped ${id}`);
      const scope = page.getByLabel("Where can this role be used?");
      await scope.selectOption((await scope.locator("option").filter({ hasText: unit }).first().getAttribute("value"))!);
      await expect(page.getByTestId("effective-access-summary")).toContainText("0 permission(s)");
      await pickPermissions(page, ["View the organization hierarchy"]);
      await expect(page.getByTestId("permission-configurator").getByRole("checkbox", { name: /^View the audit trail/ })).toBeDisabled();
      await page.getByRole("button", { name: "Create role" }).click();
      await expect(page.getByText(/Usable only within/)).toContainText(unit);
      await page.goto("/access/roles?type=custom");
      await expect(page.getByRole("table", { name: "Roles" }).getByRole("row", { name: new RegExp(`E2E ${label} scoped ${id}`) })).toContainText(unit);
    }
  });

  test("role assignment overview", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, `/access/assignments?role=${INSTITUTION_ADMIN_ROLE}`);
    const table = page.getByRole("table", { name: "Role assignments" });
    await expect(table.getByRole("link", { name: "Ines InstAdmin" })).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });

  for (const path of ["/access/roles", "/access/roles/new", "/access/permissions", "/access/assignments", `/access/roles/${INSTITUTION_ADMIN_ROLE}`,
    `/access/roles/compare?ids=${ORG_ADMIN_ROLE},${INSTITUTION_ADMIN_ROLE}`]) {
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
});
