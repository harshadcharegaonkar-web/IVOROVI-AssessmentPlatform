import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help} ${process.env.AXE_DEBUG ? JSON.stringify(v.nodes.map((n) => n.target)) : ""}`.trim());
}

async function noHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`.toUpperCase();

test.describe("Increment 1.7: dashboards, reports, notifications", () => {
  test("executive dashboard shows permission-scoped key figures", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByTestId("kpi-students")).not.toHaveText("—");
    await expect(page.getByTestId("org-kpis")).toBeVisible();
    await expect(page.getByTestId("academic-kpis")).toBeVisible();
    await expect(page.getByTestId("import-kpis")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
  });

  test("institution admin sees no integration areas and no organization-only sections", async ({ page }) => {
    await signIn(page, USERS.instAdmin);
    await expect(page.getByTestId("kpi-students")).toBeVisible();
    await expect(page.getByRole("link", { name: "Integrations" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Reports" }).first()).toBeVisible();
    await page.goto("/integrations");
    await expect(page.getByText(/permission|not available|access/i).first()).toBeVisible();
  });

  for (const [path, heading] of [["/dashboards/academic", "Academic dashboard"], ["/dashboards/staff", "Staff dashboard"],
    ["/dashboards/imports", "Import operations"], ["/students", "Students"]] as const) {
    test(`${heading} renders accessibly`, async ({ page }) => {
      await signIn(page, USERS.orgAdmin, path);
      await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
      await expect(page.locator(".animate-pulse")).toHaveCount(0);
      expect(await seriousViolations(page)).toEqual([]);
      await noHorizontalScroll(page);
    });
  }

  test("student trends offer a table view", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/students");
    const card = page.getByTestId("trend-admissions");
    await card.getByRole("button", { name: "Show table" }).click();
    await expect(card.getByRole("table")).toBeVisible();
    await expect(card.getByRole("row")).toHaveCount(13);
  });

  test("reports: filter, page and export CSV", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/reports");
    await page.getByTestId("report-catalogue").getByRole("link", { name: "Students", exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Students" })).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
    await page.getByLabel("Status").selectOption("ACTIVE");
    await expect(page).toHaveURL(/status=ACTIVE/);
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "CSV" }).click()]);
    expect(download.suggestedFilename()).toBe("ivorovi-students.csv");
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("notification center lists history with filters", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/notifications");
    await expect(page.getByRole("heading", { level: 1, name: "Notifications" })).toBeVisible();
    await page.getByRole("button", { name: /^Imports/ }).click();
    await expect(page).toHaveURL(/category=imports/);
    await page.getByRole("tab", { name: /Unread/ }).click();
    await expect(page).toHaveURL(/state=UNREAD/);
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("mobile dashboard and reports fit the screen @mobile", async ({ page }) => {
    await signIn(page, USERS.orgAdmin);
    await expect(page.getByTestId("kpi-students")).toBeVisible();
    await noHorizontalScroll(page);
    await page.goto("/dashboards/academic");
    await expect(page.getByRole("heading", { level: 1, name: "Academic dashboard" })).toBeVisible();
    await noHorizontalScroll(page);
  });

  test("tablet integrations list fits @tablet", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/integrations");
    await expect(page.getByRole("heading", { level: 1, name: "Integrations" })).toBeVisible();
    await noHorizontalScroll(page);
  });
});

test.describe("Increment 1.7: integrations, API clients, webhooks", () => {
  test("create a REST integration, test it and see the run history", async ({ page, request }) => {
    const name = `E2E SIS ${stamp()}`;
    await signIn(page, USERS.orgAdmin, "/integrations");
    await page.getByRole("button", { name: "New integration" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill(name);
    await dialog.getByLabel("Base URL").fill("http://localhost:8080/actuator");
    await dialog.getByLabel(/Health check path/).fill("/health");
    await dialog.getByLabel("Authentication", { exact: true }).selectOption("NONE");
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
    await page.getByRole("button", { name: "Test connection" }).click();
    await expect(page.getByTestId("integration-status")).toHaveText("Healthy");
    await expect(page.getByRole("table", { name: "Execution history" }).getByText("Succeeded")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    // cleanup through the API (disabled integrations can be deleted)
    const token = await apiToken(request, USERS.orgAdmin);
    const id = page.url().split("/").pop()!;
    expect((await request.delete(`/api/v1/integrations/${id}`, { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(204);
  });

  test("API client key is shown once, works read-only and can be revoked", async ({ page, request }) => {
    const name = `E2E client ${stamp()}`;
    await signIn(page, USERS.orgAdmin, "/integrations/api-clients");
    await page.getByRole("button", { name: "Register client" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill(name);
    await dialog.getByLabel(/View academic structure/).check();
    await dialog.getByRole("button", { name: "Register" }).click();
    const key = (await page.getByTestId("secret-once").textContent())!.trim();
    expect(key).toMatch(/^ivk_[0-9a-f]{24}_/);
    expect((await request.get("/api/v1/academics/programs?size=1", { headers: { "X-Api-Key": key } })).status()).toBe(200);
    expect((await request.get("/api/v1/users?size=1", { headers: { "X-Api-Key": key } })).status()).toBe(403);
    expect((await request.post("/api/v1/academics/programs", { headers: { "X-Api-Key": key }, data: {} })).status()).toBe(403);
    await page.getByRole("button", { name: "I have stored it" }).click();
    await expect(page.getByTestId("secret-once")).toHaveCount(0);
    await page.getByRole("link", { name }).click();
    await expect(page.getByText(key)).toHaveCount(0);   // never shown again
    await page.getByRole("button", { name: "Revoke client" }).click();
    await page.getByRole("dialog").getByLabel(/Reason/).fill("E2E cleanup");
    await page.getByRole("dialog").getByRole("button", { name: "Revoke" }).click();
    await expect(page.getByText("Revoked").first()).toBeVisible();
    expect((await request.get("/api/v1/academics/programs?size=1", { headers: { "X-Api-Key": key } })).status()).toBe(401);
  });

  test("webhook endpoint: secret once, failed test delivery recorded, pause and delete", async ({ page }) => {
    const name = `E2E hook ${stamp()}`;
    await signIn(page, USERS.orgAdmin, "/integrations/webhooks");
    await page.getByRole("button", { name: "New endpoint" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill(name);
    await dialog.getByLabel("Endpoint URL").fill("http://localhost:9/hook");   // nothing listens: the delivery fails and is recorded
    await dialog.getByLabel(/Student created/).check();
    await dialog.getByRole("button", { name: "Create" }).click();
    await expect(page.getByTestId("secret-once")).toContainText("whsec_");
    await page.getByRole("link", { name }).click();
    await page.getByRole("button", { name: "Send test" }).click();
    await expect(page.getByRole("table", { name: "Deliveries" }).getByText("webhook.ping")).toBeVisible();
    await page.getByRole("button", { name: "Pause" }).click();
    await expect(page.getByTestId("webhook-status")).toHaveText("Paused");
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("button", { name: "Delete endpoint" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Webhooks" })).toBeVisible();
  });
});
