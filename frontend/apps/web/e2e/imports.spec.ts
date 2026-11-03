import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

const AIT = "0192a000-0000-7000-8000-00000000a101";
const CSE = "0192a000-0000-7000-8000-00000000a121";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help} ${process.env.AXE_DEBUG ? JSON.stringify(v.nodes.map((n) => [n.target, n.any[0]?.message])) : ""}`.trim());
}

async function noHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`.toUpperCase();

/** A batch for the imported students, created through the real API. */
async function batch(request: APIRequestContext, id: string): Promise<string> {
  const token = await apiToken(request, USERS.orgAdmin);
  const h = () => ({ Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-imp-${id}-${crypto.randomUUID()}` });
  const post = async (path: string, data: unknown) => {
    const r = await request.post(path, { headers: h(), data });
    expect(r.status(), await r.text()).toBe(201);
    return (await r.json()) as Record<string, unknown> & { id?: string; year?: { id: string } };
  };
  const program = await post("/api/v1/academics/programs", { code: `IP${id}`.slice(0, 30), name: `E2E Import Program ${id}`, qualificationLevel: "UNDERGRADUATE",
    ownerOrgUnitId: AIT, termSystem: "SEMESTER", durationYears: 4 });
  const course = await post("/api/v1/academics/courses", { code: `IC${id}`.slice(0, 30), name: `E2E Import Course ${id}`, programId: program.id, departmentId: CSE });
  const years = await request.get(`/api/v1/academics/academic-years?ownerOrgUnitId=${AIT}&size=100&sort=startDate&direction=desc`, { headers: { Authorization: `Bearer ${token}` } });
  const items = ((await years.json()) as { items: { endDate: string }[] }).items;
  const y = Math.max(2060, items.length > 0 ? Number(items[0]!.endDate.slice(0, 4)) + 1 : 2060);
  const year = await post("/api/v1/academics/academic-years", { code: `IY${y}-${id}`.slice(0, 30), name: `${y} import E2E`, ownerOrgUnitId: AIT,
    startDate: `${y}-07-01`, endDate: `${y + 1}-06-30` });
  await post("/api/v1/academics/batches", { code: `IB-${id}`, name: `E2E Import Batch ${id}`, courseId: course.id, academicYearId: year.year!.id, admissionYear: y, capacity: 100 });
  return `IB-${id}`;
}

/** Letters-only suffix (names may not contain digits) so every run creates new people. */
const letters = (id: string) => id.replace(/[0-9]/g, (d) => "abcdefghij"[Number(d)]!).toLowerCase();

function csv(id: string, batchCode: string): Buffer {
  const n = letters(id);
  const ph = String(Date.now()).slice(-6);
  const d = new Date(Date.now() - 20 * 86400_000).toISOString().slice(0, 10);
  const lines = [
    "Student ID,First name,Last name,Date of birth,Personal email,Mobile,Admission number,Admission date,Batch,Student status,Guardian 1 name,Guardian 1 relationship,Guardian 1 mobile",
    `IMP-${id}-1,Kavya,Menon${n},2006-03-01,kavya.${id.toLowerCase()}@example.test,+91 98${ph}1,ADM-${id}-1,${d},${batchCode},Active,Lata Menon,Mother,+91 90000 11111`,
    `IMP-${id}-2,Arjun,Pillai${n},12/05/2006,arjun.${id.toLowerCase()}@example.test,+91 98${ph}2,ADM-${id}-2,${d},${batchCode},,Ravi Pillai,Father,+91 90000 22222`,
    `IMP-${id}-3,Nisha,Rao${n},2006-07-19,not-an-email,+91 98450 33333,ADM-${id}-3,${d},${batchCode},,,,`,
    `IMP-${id}-1,Kavya,Menon${n},2006-03-01,kavya.${id.toLowerCase()}@example.test,+91 98${ph}1,ADM-${id}-1,${d},${batchCode},Active,Lata Menon,Mother,+91 90000 11111`,
  ];
  return Buffer.from(lines.join("\n") + "\n", "utf-8");
}

test.describe("Increment 1.6: student bulk import", () => {
  test("upload → map → validate → preview → confirm → background import → result and reports", async ({ page, request }) => {
    test.setTimeout(180_000);
    const id = stamp();
    const batchCode = await batch(request, id);
    await signIn(page, USERS.orgAdmin, "/students/imports");
    await expect(page.getByRole("heading", { name: "Student imports", level: 1 })).toBeVisible();
    await expect(page.getByTestId("kpi-running")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);

    await page.getByRole("link", { name: "New import" }).first().click();
    await expect(page.getByRole("heading", { name: "New student import" })).toBeVisible();
    await page.getByLabel("University or college").selectOption(AIT);
    await page.getByLabel("Spreadsheet").setInputFiles({ name: `intake-${id}.csv`, mimeType: "text/csv", buffer: csv(id, batchCode) });
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("button", { name: "Upload and continue" }).click();

    // mapping: every heading is matched automatically
    await expect(page.getByRole("heading", { name: "Map columns" })).toBeVisible();
    await expect(page.getByLabel('Ivorovi field for "Personal email"')).toHaveValue("personal_email");
    await expect(page.getByText("Preview of mapped values")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("button", { name: "Save mapping and validate" }).click();

    // validation runs in the background
    await expect(page.getByRole("heading", { name: "Validation" })).toBeVisible();
    await expect(page.getByTestId("v-invalid")).toContainText("1", { timeout: 60_000 });
    await expect(page.getByTestId("v-valid")).toContainText("2");
    await expect(page.getByText("Not a valid email address.").first()).toBeVisible();
    await expect(page.getByText("Personal email (column 5)").first()).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("link", { name: "Continue to preview" }).click();

    await expect(page.getByRole("heading", { name: "Preview" })).toBeVisible();
    await expect(page.getByTestId("p-create")).toContainText("2");
    await expect(page.getByTestId("p-duplicates")).toContainText("1");
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("radio", { name: /Valid rows only/ }).click();
    await page.getByRole("link", { name: "Review and confirm" }).click();

    await expect(page.getByRole("heading", { name: "Confirm import" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Start import" })).toBeDisabled();
    await page.getByRole("checkbox").check();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("button", { name: "Start import" }).click();

    await expect(page.getByRole("heading", { name: "Import progress" })).toBeVisible();
    await expect(page.getByRole("progressbar", { name: "Import progress" })).toBeVisible();
    await expect(page.getByRole("link", { name: "View result" })).toBeVisible({ timeout: 90_000 });
    await expect(page.getByTestId("g-imported")).toContainText("2");
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
    await page.getByRole("link", { name: "View result" }).click();

    await expect(page.getByRole("heading", { name: "Import result" })).toBeVisible();
    await expect(page.getByText("Import partially completed")).toBeVisible();
    await expect(page.getByTestId("r-imported")).toContainText("2");
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Result report as CSV" }).click();
    expect((await download).suggestedFilename()).toBe(`intake-${id}-result.csv`);
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);

    await page.getByRole("link", { name: "View the error report" }).click();
    await expect(page.getByRole("heading", { name: "Error report" })).toBeVisible();
    await expect(page.getByText("Not a valid email address.").first()).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);

    // the imported student exists with the batch and status from the file
    await page.goto(`/students/list?q=IMP-${id}-1`);
    await expect(page.getByText(`IMP-${id}-1`).first()).toBeVisible();

    await page.goto("/students/imports/history");
    await expect(page.getByRole("heading", { name: "Import history" })).toBeVisible();
    await expect(page.getByText(`intake-${id}.csv`).first()).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
  });

  test("templates, saved mappings and matching rules", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/students/imports/templates");
    await expect(page.getByRole("heading", { name: "Import templates and rules" })).toBeVisible();
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Excel template" }).click();
    expect((await download).suggestedFilename()).toBe("ivorovi-student-import-template.xlsx");
    await expect(page.getByText("Duplicate-matching rules")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await noHorizontalScroll(page);
  });

  test("users without the import permission do not see imports", async ({ page }) => {
    await signIn(page, USERS.student, "/students/imports");
    await expect(page.getByRole("link", { name: "Student Imports" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Student imports", level: 1 })).toHaveCount(0);
  });

  for (const tag of ["@tablet", "@mobile"]) {
    test(`import screens fit without horizontal scrolling ${tag}`, async ({ page, request }) => {
      test.setTimeout(120_000);
      const id = stamp();
      const token = await apiToken(request, USERS.orgAdmin);
      const up = await request.post("/api/v1/student-imports", { headers: { Authorization: `Bearer ${token}` }, multipart: {
        file: { name: `responsive-${id}.csv`, mimeType: "text/csv", buffer: Buffer.from(`Student ID,First name,Last name,Personal email\nRSP-${id},Mina,Joshi,bad-email\nRSP-${id}-2,Ravi,Joshi,\n`) },
        institutionId: AIT } });
      expect(up.status(), await up.text()).toBe(201);
      const job = (await up.json()) as { id: string };
      await signIn(page, USERS.orgAdmin, "/students/imports");
      await expect(page.getByTestId("kpi-running")).toBeVisible();
      await noHorizontalScroll(page);
      for (const path of ["/students/imports/new", "/students/imports/history", "/students/imports/templates", `/students/imports/${job.id}/mapping`]) {
        await page.goto(path);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await noHorizontalScroll(page);
      }
      await page.getByRole("button", { name: "Save mapping and validate" }).click();
      await expect(page.getByTestId("v-invalid")).toContainText("1", { timeout: 60_000 });
      await noHorizontalScroll(page);
      for (const step of ["preview", "confirm", "errors"]) {
        await page.goto(`/students/imports/${job.id}/${step}`);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await noHorizontalScroll(page);
      }
    });
  }
});
