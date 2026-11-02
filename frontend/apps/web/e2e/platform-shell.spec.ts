import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { signIn, USERS } from "./support";

test.describe("Application shell and Phase 0 workflows", () => {
  test("org admin signs in through Keycloak and sees their tenant workspace", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByTestId("tenant-name")).toHaveText("Aurora Education Group");
    await expect(page.getByTestId("user-name")).toHaveText("Alice Admin");
    await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
    await expect(page.getByTestId("kpi-universities")).toHaveText("1");
    await expect(page.getByTestId("kpi-colleges")).toHaveText("2");
    await expect(page.getByTestId("kpi-campuses")).toHaveText("1");
    await expect(page.getByTestId("kpi-departments")).toHaveText("3");
    await expect(page.getByTestId("org-distribution")).toContainText("Aurora Institute of Technology");
    const nav = page.getByRole("navigation", { name: "Main" });
    for (const item of ["Dashboard", "Organization Hierarchy", "Universities", "Colleges", "Campuses", "Departments",
      "Feature Settings", "Audit Logs", "System Settings"]) {
      await expect(nav.getByRole("link", { name: item })).toBeVisible();
    }
  });

  test("colleges: search and detail with institution features", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/organization/colleges");
    const table = page.getByRole("table", { name: "Colleges" });
    await expect(table.getByRole("row")).toHaveCount(3); // header + 2
    await page.getByRole("searchbox", { name: "Search colleges" }).fill("commerce");
    await expect(table.getByRole("row")).toHaveCount(2);
    await expect(page).toHaveURL(/q=commerce/);
    await table.getByRole("link", { name: "Aurora College of Commerce" }).click();
    await expect(page.getByRole("heading", { name: "Aurora College of Commerce", level: 1 })).toBeVisible();
    await page.getByRole("tab", { name: "Features" }).click();
    await expect(page.getByRole("heading", { name: "Institution features" })).toBeVisible();
  });

  test("other tenant's data is not reachable from the UI", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/organization/units/0192b000-0000-7000-8000-00000000b101");
    await expect(page.getByText("Unit not found")).toBeVisible();
    await expect(page.getByText("Borealis")).toHaveCount(0);
  });

  test("feature toggle persists, is audited, and AI requires confirmation", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/settings/features");
    const row = page.getByTestId("feature-module.proctoring");
    const toggle = row.getByRole("switch");
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await toggle.click();
    await expect(page.getByRole("status").filter({ hasText: "Feature updated" })).toBeVisible();
    await expect(toggle).toHaveAttribute("aria-checked", "true");

    await page.reload();
    await expect(page.getByTestId("feature-module.proctoring").getByRole("switch")).toHaveAttribute("aria-checked", "true");

    // AI: confirmation dialog, cancel leaves it off
    const ai = page.getByTestId("feature-ai.evaluation").getByRole("switch");
    await ai.click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("require approval by an authorised person");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(ai).toHaveAttribute("aria-checked", "false");

    // reset the override so the test is repeatable
    await page.getByTestId("feature-module.proctoring").getByRole("button", { name: "Reset" }).click();
    await expect(page.getByTestId("feature-module.proctoring").getByRole("switch")).toHaveAttribute("aria-checked", "false");

    await page.goto("/audit");
    await page.getByLabel("Action").fill("feature.override.changed");
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page.getByRole("table", { name: "Audit events" }).getByText("flag=module.proctoring").first()).toBeVisible();
    await page.getByRole("button", { name: "Verify integrity" }).click();
    await expect(page.getByText("Audit trail verified")).toBeVisible();
  });

  test("student sees only permitted navigation and is denied admin pages", async ({ page }) => {
    await signIn(page, USERS.student);
    const nav = page.getByRole("navigation", { name: "Main" });
    await expect(nav.getByRole("link", { name: "Dashboard" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Audit Logs" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Organization Hierarchy" })).toHaveCount(0);
    await expect(page.getByText("Organization", { exact: true })).toHaveCount(0); // empty groups are hidden
    await page.goto("/audit");
    await expect(page.getByText("Access denied")).toBeVisible();
  });

  test("institution admin can only manage their institution", async ({ page }) => {
    await signIn(page, USERS.instAdmin, "/settings/features");
    const scope = page.getByLabel("Scope");
    await expect(scope.locator("option")).toHaveCount(1);
    await expect(scope).toHaveValue("0192a000-0000-7000-8000-00000000a101");
    await expect(page.getByRole("heading", { name: "Institution features" })).toBeVisible();
  });

  test("identity without an Ivorovi account cannot open a workspace", async ({ page }) => {
    await signIn(page, USERS.noAccount);
    await expect(page.getByText("We could not open your workspace")).toBeVisible();
    await expect(page.getByText(/no_active_account/)).toBeVisible();
  });

  test("dashboard has no serious accessibility violations", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    const serious = results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    expect(serious.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  test("mobile navigation drawer @mobile", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await page.getByRole("button", { name: "Open navigation" }).click();
    await page.locator("#mobile-nav").getByRole("link", { name: "Organization Hierarchy" }).click();
    await expect(page.getByRole("heading", { name: "Organization hierarchy", level: 1 })).toBeVisible();
    await expect(page.locator("#mobile-nav")).toHaveCount(0);
  });

  test("sidebar collapses to an icon rail and remembers the preference", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    const sidebar = page.getByTestId("sidebar");
    await page.getByRole("button", { name: "Collapse sidebar" }).click();
    await expect(sidebar).toHaveCSS("width", "72px");
    await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Feature Settings" })).toBeVisible(); // icon link keeps its accessible name
    await page.reload();
    await expect(page.getByTestId("sidebar")).toHaveCSS("width", "72px");
    await page.getByRole("button", { name: "Expand sidebar" }).click();
    await expect(page.getByTestId("sidebar")).toHaveCSS("width", "256px");
  });

  test("breadcrumbs, global search and account menu", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/settings/features");
    const crumbs = page.getByRole("navigation", { name: "Breadcrumb" });
    await expect(crumbs.getByRole("link", { name: "Dashboard" })).toBeVisible();
    await expect(crumbs.getByText("Feature Settings")).toHaveAttribute("aria-current", "page");

    await page.keyboard.press("Control+k");
    const search = page.getByRole("combobox", { name: "Search pages and records" });
    await search.fill("commerce");
    await expect(page.getByRole("option", { name: /Aurora College of Commerce/ })).toBeVisible();
    await search.press("Enter");
    await expect(page.getByRole("heading", { name: "Aurora College of Commerce", level: 1 })).toBeVisible();

    await page.getByRole("button", { name: "Account menu" }).click();
    await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
    await page.getByRole("menuitem", { name: "My Access" }).click();
    await expect(page.getByRole("heading", { name: "My access", level: 1 })).toBeVisible();
  });

  test("notification centre lists the caller's notifications", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await page.getByRole("button", { name: /^Notifications/ }).click();
    const panel = page.getByRole("dialog", { name: "Notifications" });
    await expect(panel.getByText("Notifications", { exact: true })).toBeVisible();
    await expect(panel.getByRole("button", { name: "Mark all as read" })).toBeVisible();
  });

  test("tablet layout uses a navy top bar and icon rail @tablet", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByTestId("sidebar")).toHaveCSS("width", "72px");
    await expect(page.locator("header")).toHaveCSS("background-color", "rgb(23, 36, 58)");
    await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Audit Logs" })).toBeVisible();
  });

  for (const path of ["/", "/settings/features", "/audit", "/me"]) {
    test(`no horizontal scrolling on ${path} @mobile`, async ({ page }) => {
      await signIn(page, USERS.orgAdmin, path);
      await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(0);
    });
  }
});
