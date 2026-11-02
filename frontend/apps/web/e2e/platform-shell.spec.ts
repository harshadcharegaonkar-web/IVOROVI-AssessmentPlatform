import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { signIn, USERS } from "./support";

test.describe("Phase 0 application shell", () => {
  test("org admin signs in through Keycloak and sees their tenant workspace", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByTestId("tenant-name")).toHaveText("Aurora Education Group");
    await expect(page.getByTestId("user-name")).toHaveText("Alice Admin");
    await expect(page.getByRole("heading", { name: "Welcome, Alice" })).toBeVisible();
    await expect(page.getByTestId("institution-count")).toHaveText("2");
    const nav = page.getByRole("navigation", { name: "Main" });
    for (const item of ["Dashboard", "Institutions", "Features", "Audit log", "My access"]) {
      await expect(nav.getByRole("link", { name: item })).toBeVisible();
    }
  });

  test("institutions: search, sort and detail", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/institutions");
    const table = page.getByRole("table", { name: "Institutions" });
    await expect(table.getByRole("row")).toHaveCount(3); // header + 2
    await page.getByRole("searchbox", { name: "Search institutions" }).fill("commerce");
    await expect(table.getByRole("row")).toHaveCount(2);
    await expect(page).toHaveURL(/q=commerce/);
    await table.getByRole("link", { name: "Aurora College of Commerce" }).click();
    await expect(page.getByRole("heading", { name: "Aurora College of Commerce" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Institution features" })).toBeVisible();
  });

  test("other tenant's data is not reachable from the UI", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/institutions/0192b000-0000-7000-8000-00000000b101");
    await expect(page.getByText("Institution not found")).toBeVisible();
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
    await expect(nav.getByRole("link", { name: "Audit log" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Institutions" })).toHaveCount(0);
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
    await expect(page.getByRole("heading", { name: "Welcome, Alice" })).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    const serious = results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    expect(serious.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  test("mobile navigation drawer @mobile", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await page.getByRole("button", { name: "Open navigation" }).click();
    await page.locator("#mobile-nav").getByRole("link", { name: "Institutions" }).click();
    await expect(page.getByRole("heading", { name: "Institutions" })).toBeVisible();
    await expect(page.locator("#mobile-nav")).toHaveCount(0);
  });
});
