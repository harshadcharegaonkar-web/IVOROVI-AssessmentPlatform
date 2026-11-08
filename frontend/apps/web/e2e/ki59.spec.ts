import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help}`);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`.toUpperCase();

test.describe("KI-59: live API-client authorization and service identities", () => {
  test("a delegated client shows what works now; suspend and resume are manual", async ({ page, request }) => {
    const name = `E2E delegated ${stamp()}`;
    await signIn(page, USERS.orgAdmin, "/integrations/api-clients");
    await page.getByRole("button", { name: "Register client" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill(name);
    await expect(dialog.getByLabel("Acts for")).toHaveValue("");
    await dialog.getByLabel(/View academic structure/).check();
    await dialog.getByRole("button", { name: "Register" }).click();
    const key = (await page.getByTestId("secret-once").textContent())!.trim();
    await page.getByRole("button", { name: "I have stored it" }).click();
    await page.getByRole("link", { name }).click();
    await expect(page.getByTestId("client-effective")).toContainText("academics.read");
    expect(await seriousViolations(page)).toEqual([]);

    await page.getByRole("button", { name: "Suspend" }).click();
    await page.getByRole("dialog").getByLabel(/Reason/).fill("E2E check");
    await page.getByRole("dialog").getByRole("button", { name: "Suspend" }).click();
    await expect(page.getByTestId("client-status")).toHaveText("Suspended");
    expect((await request.get("/api/v1/academics/programs?size=1", { headers: { "X-Api-Key": key } })).status()).toBe(401);
    await page.getByRole("button", { name: "Resume" }).click();
    await expect(page.getByTestId("client-status")).toHaveText("Active");
    expect((await request.get("/api/v1/academics/programs?size=1", { headers: { "X-Api-Key": key } })).status()).toBe(200);

    const token = await apiToken(request, USERS.orgAdmin);
    const id = page.url().split("/").pop()!;
    expect((await request.post(`/api/v1/api-clients/${id}/revoke`, { headers: { Authorization: `Bearer ${token}` }, data: { reason: "E2E cleanup" } })).status()).toBe(200);
  });

  test("a service identity waits for approval by someone other than its proposer", async ({ page, request }) => {
    const name = `E2E identity ${stamp()}`;
    await signIn(page, USERS.orgAdmin, "/integrations/service-identities");
    await page.getByRole("button", { name: "New service identity" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill(name);
    await dialog.getByLabel("Purpose").fill("Nightly roster export (E2E)");
    await dialog.getByLabel("Business custodian").selectOption({ index: 1 });
    await dialog.getByLabel("Technical custodian").selectOption({ index: 2 });
    await dialog.getByLabel(/View the organization hierarchy/).check();
    await dialog.getByRole("button", { name: "Submit for approval" }).click();
    await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
    await expect(page.getByTestId("identity-status")).toContainText("Pending approval");
    await expect(page.getByRole("button", { name: "Approve" })).toBeDisabled();
    await expect(page.getByText("another integration manager must approve it")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    const token = await apiToken(request, USERS.orgAdmin);
    const id = page.url().split("/").pop()!;
    expect((await request.post(`/api/v1/service-identities/${id}/envelope/approve`, { headers: { Authorization: `Bearer ${token}` }, data: {} })).status()).toBe(409);
    expect((await request.post(`/api/v1/service-identities/${id}/disable`, { headers: { Authorization: `Bearer ${token}` }, data: { reason: "E2E cleanup" } })).status()).toBe(200);
  });
});
