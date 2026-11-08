import base from "./playwright.config";
import { defineConfig } from "@playwright/test";

// Demonstration capture only (see demo.capture.ts); not used by CI.
export default defineConfig({ ...base, testMatch: "demo.capture.ts", projects: [base.projects![0]!], retries: 0 });
