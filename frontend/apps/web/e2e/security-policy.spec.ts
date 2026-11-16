import { expect, test, type APIRequestContext } from "@playwright/test";
import { apiToken, signIn } from "./support";

/**
 * Mandatory module I (demo programme): the organization security policy. A stronger rule applies at once; a weaker one is
 * applied only after a second administrator approves it. Runs in Borealis, which has two administrators (dev fixture
 * V202611191011), and always ends with the policy back at its baseline (no limits). The second factor is not switched
 * on here: it would require an authenticator app at every later sign-in of these accounts (covered by SecurityPolicyIT).
 */
const REQUESTER = "bela.admin@borealis.test";
const APPROVER = "bruno.admin@borealis.test";

type Policy = { mfaRequired?: boolean; idleMinutes?: number; absoluteHours?: number; version?: number; pendingApprovalId?: string };

async function policy(request: APIRequestContext, user: string): Promise<Policy> {
  const res = await request.get("/api/v1/tenant/security-policy", { headers: { Authorization: `Bearer ${await apiToken(request, user)}` } });
  expect(res.ok()).toBeTruthy();
  return await res.json() as Policy;
}

/** Returns Borealis to no limits (left over by an interrupted run): approve a pending request, or request and approve one. */
async function restoreBaseline(request: APIRequestContext): Promise<void> {
  let p = await policy(request, REQUESTER);
  if (p.idleMinutes == null && p.absoluteHours == null && !p.mfaRequired && !p.pendingApprovalId) return;
  let approval = p.pendingApprovalId;
  if (!approval) {
    const res = await request.put("/api/v1/tenant/security-policy", {
      headers: { Authorization: `Bearer ${await apiToken(request, REQUESTER)}` }, data: { mfaRequired: false, version: p.version ?? 0 } });
    expect(res.ok()).toBeTruthy();
    approval = ((await res.json()) as { approvalRequestId: string }).approvalRequestId;
  }
  const decided = await request.post(`/api/v1/approvals/${approval}/decision`, {
    headers: { Authorization: `Bearer ${await apiToken(request, APPROVER)}`, "Idempotency-Key": crypto.randomUUID() }, data: { approve: true, reason: "E2E reset" } });
  expect(decided.ok()).toBeTruthy();
  p = await policy(request, REQUESTER);
  expect(p.idleMinutes ?? null).toBeNull();
}

test.describe("security policy", () => {
  test.beforeEach(async ({ request }) => restoreBaseline(request));
  test.afterEach(async ({ request }) => restoreBaseline(request));

  test("a stronger policy applies at once; a weaker one waits for a second administrator", async ({ page, browser }) => {
    await signIn(page, REQUESTER, "/settings/security");
    await expect(page.getByRole("heading", { name: "Security Policy" })).toBeVisible();
    await expect(page.getByTestId("password-policy")).toContainText("cannot differ per organization");

    // stronger: applied at once
    await page.getByTestId("idle-minutes").fill("30");
    await page.getByTestId("absolute-hours").fill("8");
    await page.getByTestId("save-policy").click();
    await expect(page.getByText("Security policy saved")).toBeVisible();
    await page.reload();
    await expect(page.getByTestId("idle-minutes")).toHaveValue("30");
    await expect(page.getByTestId("absolute-hours")).toHaveValue("8");

    // client-side validation
    await page.getByTestId("idle-minutes").fill("3");
    await expect(page.getByText("Between 5 and 1440")).toBeVisible();
    await expect(page.getByTestId("save-policy")).toBeDisabled();

    // weaker: no limits; waits for approval, the current policy stays in force
    await page.getByTestId("idle-minutes").fill("");
    await page.getByTestId("absolute-hours").fill("");
    await page.getByTestId("save-policy").click();
    await expect(page.getByText(/needs a second administrator's approval/)).toBeVisible();
    await expect(page.getByTestId("policy-pending")).toBeVisible();
    await page.reload();
    await expect(page.getByTestId("idle-minutes")).toHaveValue("30");
    await page.getByRole("link", { name: "View the request" }).click();
    await expect(page.getByText("You requested this")).toBeVisible();                  // the requester cannot decide
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    const approvalUrl = new URL(page.url()).pathname;

    // the second administrator approves
    const other = await browser.newContext();
    const approver = await other.newPage();
    await signIn(approver, APPROVER, approvalUrl);
    await approver.getByRole("button", { name: "Approve" }).click();
    await expect(approver.getByText(/approved/i).first()).toBeVisible();
    await other.close();

    await page.goto("/settings/security");
    await expect(page.getByTestId("idle-minutes")).toHaveValue("");
    await expect(page.getByTestId("absolute-hours")).toHaveValue("");
    await expect(page.getByTestId("policy-pending")).toHaveCount(0);
  });
});
