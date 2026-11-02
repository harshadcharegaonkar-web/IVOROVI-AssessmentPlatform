import { expect, type Page } from "@playwright/test";

export const USERS = {
  orgAdmin: "alice.admin@aurora.test",
  instAdmin: "ines.instadmin@aurora.test",
  student: "asha.student@aurora.test",
  otherTenantAdmin: "bela.admin@borealis.test",
  noAccount: "ghost.user@aurora.test",
} as const;

export function password(): string {
  const p = process.env.DEV_USER_PASSWORD;
  if (!p) throw new Error("DEV_USER_PASSWORD must be set (see deploy/compose/.env)");
  return p;
}

/** Signs in through the real Keycloak login page. */
export async function signIn(page: Page, username: string, path = "/"): Promise<void> {
  await page.goto(path);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password());
  await page.locator("#kc-login").click();
  await expect(page.locator("#username")).toHaveCount(0);
}
