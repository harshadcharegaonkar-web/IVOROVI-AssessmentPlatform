import { defineConfig, devices } from "@playwright/test";

/**
 * Exam application end to end, against a running stack (real candidate realm, delivery process, staff API).
 *   E2E_BASE_URL   exam app origin (default: Vite dev server http://localhost:5174; CI: exam container http://localhost:8082)
 *   E2E_STAFF_URL  staff app origin, for staff screens and API housekeeping (default http://localhost:5173)
 *   DELIVERY_URL   delivery process, for boundary checks (default http://localhost:8090)
 *   E2E_HEADERS_URL origin serving the production build with production headers (default: E2E_BASE_URL)
 */
export default defineConfig({
  testDir: ".",
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5174", trace: "retain-on-failure", screenshot: "only-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } }, grepInvert: /@mobile|@tablet/ },
    { name: "tablet", use: { ...devices["Desktop Chrome"], viewport: { width: 1024, height: 768 } }, grep: /@tablet/ },
    { name: "mobile", use: { ...devices["Pixel 7"] }, grep: /@mobile/ },
  ],
});
