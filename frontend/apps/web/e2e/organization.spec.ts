import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { deleteUnitsNamed, signIn, USERS } from "./support";

const AIT = "0192a000-0000-7000-8000-00000000a101";
const ACC = "0192a000-0000-7000-8000-00000000a102";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help}`);
}

/**
 * Opens "More actions" and picks an item. KI-65: right after a navigation the lazily loaded page may still re-render,
 * which closes a menu opened too early; wait for the open menu (re-opening if needed) instead of for element stability.
 */
async function moreAction(page: Page, name: string | RegExp) {
  const item = page.getByRole("menuitem", { name });
  await expect(async () => {
    if (!(await item.isVisible())) await page.getByRole("button", { name: "More actions" }).click();
    await expect(page.getByRole("menu")).toBeVisible({ timeout: 2000 });
    await expect(item).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 20_000 });
  await item.click();
}

test.describe("Increment 1.1: organization hierarchy", () => {
  // Units created by the lifecycle test are removed through the UI; this also cleans up after a failed run.
  test.beforeAll(async ({ request }) => { await deleteUnitsNamed(request, "E2E "); });
  test.afterAll(async ({ request }) => { await deleteUnitsNamed(request, "E2E "); });

  test("hierarchy overview: tree, filter and selected unit with its path", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/organization");
    const tree = page.getByRole("tree", { name: "Organization hierarchy" });
    await expect(tree.getByRole("treeitem").filter({ hasText: "Aurora University" }).first()).toBeVisible();
    await expect(tree.getByRole("treeitem").filter({ hasText: "AIT North Campus" }).first()).toBeVisible();

    await page.getByRole("searchbox", { name: "Filter the hierarchy" }).fill("mechanical");
    await expect(tree.getByRole("treeitem").filter({ hasText: "Aurora College of Commerce" })).toHaveCount(0);
    const mech = tree.getByRole("treeitem", { name: /Mechanical/ }).last();
    await mech.locator("> div").click();
    await expect(page).toHaveURL(/unit=0192a000-0000-7000-8000-00000000a122/);
    const panel = page.getByRole("heading", { name: /Mechanical/ }).locator("xpath=ancestor::*[contains(@class,'rounded')][1]");
    await expect(panel.getByRole("navigation", { name: "Breadcrumb" })).toContainText("Aurora University");
    await expect(panel.getByRole("navigation", { name: "Breadcrumb" })).toContainText("AIT North Campus");
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("lifecycle: create, validate, edit, move, deactivate, activate, delete — with history", async ({ page }) => {
    const code = `E2E${Date.now().toString(36).toUpperCase()}`;
    await signIn(page, USERS.orgAdmin, "/organization/colleges");

    // create a stand-alone college
    await page.getByRole("button", { name: "New college" }).click();
    let dialog = page.getByRole("dialog", { name: "New college" });
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(dialog.getByText("Code is required")).toBeVisible();          // client-side validation
    await dialog.getByLabel("Code").fill("au");                               // Aurora University: duplicate among top-level units (case-insensitive)
    await dialog.getByLabel("Name", { exact: true }).fill(`E2E College ${code}`);
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(dialog.getByText(/already/i)).toBeVisible();                 // server-side uniqueness, shown on the field
    await dialog.getByLabel("Code").fill(code);
    await dialog.getByLabel("City").fill("Pune");
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(page.getByRole("heading", { name: `E2E College ${code}`, level: 1 })).toBeVisible();
    const collegeUrl = page.url();

    // add a campus, then a department under it (codes may repeat under different parents)
    await page.getByRole("button", { name: "Add sub-unit" }).click();
    await page.getByRole("menuitem", { name: "Campus" }).click();
    dialog = page.getByRole("dialog", { name: "New campus" });
    await dialog.getByLabel("Code").fill("MAIN");
    await dialog.getByLabel("Name", { exact: true }).fill(`E2E Main Campus ${code}`);
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(page.getByRole("heading", { name: `E2E Main Campus ${code}`, level: 1 })).toBeVisible();
    const crumbs = page.getByRole("main").getByRole("navigation", { name: "Breadcrumb" }).first();
    await expect(crumbs.getByRole("link", { name: `E2E College ${code}` })).toBeVisible();

    await page.getByRole("button", { name: "Add sub-unit" }).click();
    await page.getByRole("menuitem", { name: "Department" }).click();
    dialog = page.getByRole("dialog", { name: "New department" });
    await dialog.getByLabel("Code").fill("CSE");
    await dialog.getByLabel("Name", { exact: true }).fill(`E2E Computer Science ${code}`);
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(page.getByRole("heading", { name: `E2E Computer Science ${code}`, level: 1 })).toBeVisible();

    // edit
    await page.getByRole("button", { name: "Edit" }).click();
    dialog = page.getByRole("dialog", { name: "Edit department" });
    await dialog.getByLabel("Name", { exact: true }).fill(`E2E Computing ${code}`);
    await dialog.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByRole("heading", { name: `E2E Computing ${code}`, level: 1 })).toBeVisible();

    // move the department from the campus directly under the college
    await moreAction(page, "Move…");
    dialog = page.getByRole("dialog", { name: `Move E2E Computing ${code}` });
    await dialog.getByLabel("New parent").selectOption({ label: `E2E College ${code} (College)` });
    await dialog.getByLabel("Reason (recorded in history)").fill("Reorganisation");
    await dialog.getByRole("button", { name: "Move" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Unit moved" })).toBeVisible();
    await expect(page.getByRole("main").getByRole("navigation", { name: "Breadcrumb" }).first()).not.toContainText("Main Campus");

    // history shows created, updated and moved
    await page.getByRole("tab", { name: "History" }).click();
    const history = page.getByRole("table", { name: "Change history" });
    await expect(history.getByText("Moved")).toBeVisible();
    await expect(history.getByText("Updated")).toBeVisible();
    await expect(history.getByText("Created")).toBeVisible();
    await expect(history.getByText(/reason: Reorganisation/)).toBeVisible();

    // deactivating a parent with active children is refused; deactivate the leaf, then reactivate it
    await page.goto(collegeUrl);
    await moreAction(page, "Deactivate…");
    dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Deactivate its 2 active sub-unit(s) first.");
    await dialog.getByRole("button", { name: "Deactivate" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Cannot deactivate" })).toBeVisible(); // refused by the server
    await page.keyboard.press("Escape");

    await page.getByRole("tab", { name: /Sub-units/ }).click();
    await page.getByRole("table", { name: "Sub-units" }).getByRole("link", { name: `E2E Computing ${code}` }).click();
    await moreAction(page, "Deactivate…");
    await page.getByRole("dialog").getByRole("button", { name: "Deactivate" }).click();
    await expect(page.getByRole("main").getByText("Inactive").first()).toBeVisible();
    await moreAction(page, "Activate…");
    await page.getByRole("dialog").getByRole("button", { name: "Activate" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Unit activated" })).toBeVisible();

    // delete leaves bottom-up; the college is "in use" until its children are gone
    await moreAction(page, "Delete…");
    await page.getByRole("dialog").getByRole("button", { name: "Delete permanently" }).click();
    await expect(page.getByRole("heading", { name: `E2E College ${code}`, level: 1 })).toBeVisible();
    await page.getByRole("button", { name: "More actions" }).click();
    await expect(page.getByText("Delete (in use)")).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("tab", { name: /Sub-units/ }).click();
    await page.getByRole("table", { name: "Sub-units" }).getByRole("link", { name: `E2E Main Campus ${code}` }).click();
    await moreAction(page, "Delete…");
    await page.getByRole("dialog").getByRole("button", { name: "Delete permanently" }).click();
    await expect(page.getByRole("heading", { name: `E2E College ${code}`, level: 1 })).toBeVisible();
    await moreAction(page, "Delete…");
    await page.getByRole("dialog").getByRole("button", { name: "Delete permanently" }).click();
    await expect(page.getByRole("heading", { name: "Organization hierarchy", level: 1 })).toBeVisible();

    // every change is in the audit trail and the chain still verifies
    await page.goto("/audit");
    await page.getByLabel("Action").fill("org_unit.deleted");
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page.getByRole("table", { name: "Audit events" }).getByRole("row").nth(1)).toBeVisible();
    await page.getByRole("button", { name: "Verify integrity" }).click();
    await expect(page.getByText("Audit trail verified")).toBeVisible();
  });

  test("listings: department filters by status and parent, sorting and empty state", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/organization/departments");
    const table = page.getByRole("table", { name: "Departments" });
    await expect(table.getByRole("row")).toHaveCount(4); // header + CSE, MECH, COM
    await page.getByLabel("Parent").selectOption({ label: "AIT North Campus" });
    await expect(table.getByRole("row")).toHaveCount(3);
    await expect(page).toHaveURL(/parent=/);
    await page.getByLabel("Status").selectOption("INACTIVE");
    await expect(page.getByText("No departments match these filters")).toBeVisible();
    await page.getByLabel("Status").selectOption("");
    await table.getByRole("button", { name: /Code/ }).click();
    await expect(page).toHaveURL(/sort=code/);
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("unit detail is accessible", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, `/organization/units/${AIT}`);
    await expect(page.getByRole("heading", { name: "Aurora Institute of Technology", level: 1 })).toBeVisible();
    await expect(page.getByRole("main").getByRole("navigation", { name: "Breadcrumb" }).first().getByRole("link", { name: "Aurora University" })).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("scoped administrator sees and manages only their subtree", async ({ page }) => {
    await signIn(page, USERS.instAdmin, "/organization");
    const tree = page.getByRole("tree", { name: "Organization hierarchy" });
    await expect(tree.getByRole("treeitem").filter({ hasText: "Aurora Institute of Technology" }).first()).toBeVisible();
    await expect(tree.getByRole("treeitem").filter({ hasText: "Aurora College of Commerce" })).toHaveCount(0);
    await expect(tree.getByRole("treeitem").filter({ hasText: "Aurora University" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "University" })).toHaveCount(0); // no top-level creation

    await page.goto(`/organization/units/${ACC}`);
    await expect(page.getByText("Access denied")).toBeVisible();

    await page.goto(`/organization/units/${AIT}`);
    await expect(page.getByRole("button", { name: "Add sub-unit" })).toBeVisible();
    await page.goto("/settings/system");
    await expect(page.getByText("Access denied")).toBeVisible();
  });

  test("system settings: terminology changes labels everywhere and can be reverted", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/settings/system");
    await expect(page.getByRole("heading", { name: "System Settings", level: 1 })).toBeVisible();
    const singular = page.getByTestId("label-COLLEGE-singular");
    const plural = page.getByTestId("label-COLLEGE-plural");
    await expect(singular).toHaveValue("College");
    expect(await seriousViolations(page)).toEqual([]);

    await singular.fill("");
    await page.getByRole("button", { name: "Save settings" }).click();
    await expect(page.getByText("Required").first()).toBeVisible();

    try {
      await singular.fill("School");
      await plural.fill("Schools");
      await page.getByRole("button", { name: "Save settings" }).click();
      await expect(page.getByRole("status").filter({ hasText: "Settings saved" })).toBeVisible();
      const nav = page.getByRole("navigation", { name: "Main" });
      await expect(nav.getByRole("link", { name: "Schools" })).toBeVisible();
      await nav.getByRole("link", { name: "Schools" }).click();
      await expect(page.getByRole("heading", { name: "Schools", level: 1 })).toBeVisible();
    } finally {
      await page.goto("/settings/system");
      await page.getByTestId("label-COLLEGE-singular").fill("College");
      await page.getByTestId("label-COLLEGE-plural").fill("Colleges");
      await page.getByRole("button", { name: "Save settings" }).click();
      await expect(page.getByRole("status").filter({ hasText: "Settings saved" })).toBeVisible();
    }
    await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Colleges" })).toBeVisible();
  });

  test("tablet: hierarchy tree and panel stack without overflow @tablet", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, `/organization?unit=${AIT}`);
    await expect(page.getByRole("tree", { name: "Organization hierarchy" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Open" })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  for (const path of ["/organization", "/organization/colleges", "/organization/departments", `/organization/units/${AIT}`, "/settings/system"]) {
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
