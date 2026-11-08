import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against a running stack (real Keycloak, API, database).
 *   E2E_BASE_URL       web origin (default: web container http://localhost:8081)
 *   DEV_USER_PASSWORD  password of the dev realm users (deploy/compose/.env)
 */
export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8081",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } }, grepInvert: /@mobile|@tablet/ },
    { name: "tablet", use: { ...devices["Desktop Chrome"], viewport: { width: 1024, height: 768 } }, grep: /@tablet/ },
    { name: "mobile", use: { ...devices["Pixel 7"] }, grep: /@mobile/ },
    // mathematics rendering across browsers (L-2.1-1): Chrome runs it in "desktop"; these run only @math tests
    { name: "math-firefox", use: { ...devices["Desktop Firefox"] }, grep: /@math/ },
    { name: "math-safari", use: { ...devices["Desktop Safari"] }, grep: /@math/ },
    { name: "math-edge", use: { ...devices["Desktop Edge"], channel: "msedge" }, grep: /@math/ },
    { name: "math-mobile-chrome", use: { ...devices["Pixel 7"] }, grep: /@math/ },
    { name: "math-mobile-safari", use: { ...devices["iPhone 14"] }, grep: /@math/ },
  ],
});
