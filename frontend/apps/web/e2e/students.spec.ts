import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, selectOptionContaining, signIn, USERS } from "./support";

const AIT = "0192a000-0000-7000-8000-00000000a101";
const CSE = "0192a000-0000-7000-8000-00000000a121";

async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id}: ${v.help}`);
}

async function noHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1);
}

const stamp = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`.toUpperCase();

/** Academic structure the students are admitted into, created through the real API (independent of other suites). */
async function structure(request: APIRequestContext, id: string) {
  const token = await apiToken(request, USERS.orgAdmin);
  const h = () => ({ Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-${id}-${crypto.randomUUID()}` });
  const post = async (path: string, data: unknown) => {
    const r = await request.post(path, { headers: h(), data });
    expect(r.status(), await r.text()).toBe(201);
    return (await r.json()) as Record<string, unknown> & { id?: string; year?: { id: string } };
  };
  const program = await post("/api/v1/academics/programs", { code: `SP${id}`.slice(0, 30), name: `E2E Students Program ${id}`, qualificationLevel: "UNDERGRADUATE",
    ownerOrgUnitId: AIT, termSystem: "SEMESTER", durationYears: 4 });
  const course = await post("/api/v1/academics/courses", { code: "CSE", name: `E2E Students CSE ${id}`, programId: program.id, departmentId: CSE });
  const years = await request.get(`/api/v1/academics/academic-years?ownerOrgUnitId=${AIT}&size=100&sort=startDate&direction=desc`, { headers: { Authorization: `Bearer ${token}` } });
  const items = ((await years.json()) as { items: { endDate: string }[] }).items;
  const y = Math.max(2060, items.length > 0 ? Number(items[0]!.endDate.slice(0, 4)) + 1 : 2060);
  const year = await post("/api/v1/academics/academic-years", { code: `SY${y}-${id}`.slice(0, 30), name: `${y} students E2E`, ownerOrgUnitId: AIT,
    startDate: `${y}-07-01`, endDate: `${y + 1}-06-30` });
  await post("/api/v1/academics/batches", { code: `SB-${id}`, name: `E2E Students Batch ${id}`, courseId: course.id, academicYearId: year.year!.id,
    admissionYear: y, capacity: 60 });
  return { programName: `E2E Students Program ${id}`, courseName: `E2E Students CSE ${id}`, yearName: `${y} students E2E`, batchName: `E2E Students Batch ${id}` };
}

test.describe("Increment 1.5: student lifecycle", () => {
  test("create → admit → enrol → activate; guardians, quarantined documents, history and account", async ({ page, request }) => {
    const id = stamp();
    const s = await structure(request, id);
    await signIn(page, USERS.orgAdmin, "/students");
    await expect(page.getByRole("heading", { name: "Students", level: 1 })).toBeVisible();
    await expect(page.getByTestId("kpi-total")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    // create (Aurora has no numbering rule: the ID is entered and validated)
    await page.getByRole("link", { name: "New student" }).first().click();
    await page.getByLabel("University or college").selectOption(AIT);
    await page.getByLabel("Student ID").fill(`E2E-${id}`);
    await page.getByLabel("First name").fill("Riya");
    await page.getByLabel("Last name (optional)").fill(`Verma ${id}`);
    await page.getByLabel("Date of birth (optional)").fill("2006-02-11");
    await page.getByLabel("Mobile (optional)").fill("+91 90000 12345");
    await page.getByLabel("Current address (optional)").fill("7 Test Lane, Pune");
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("button", { name: "Create student" }).click();
    await expect(page.getByRole("heading", { name: `Riya Verma ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByText("Prospective").first()).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    const profile = page.url();

    // personal information tab
    await page.getByRole("tab", { name: "Personal information" }).click();
    await expect(page.getByText("+91 90000 12345")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    // admission (separate record) → admitted
    await page.getByRole("tab", { name: "Academic" }).click();
    await page.getByRole("button", { name: "Record admission" }).click();
    const ad = page.getByRole("dialog", { name: "Record admission" });
    await ad.getByLabel("Admission number").fill(`ADM-${id}`);
    await ad.getByLabel("Program").selectOption({ label: `${s.programName} · Aurora Institute of Technology` });
    await selectOptionContaining(ad.getByLabel("Course"), s.courseName);
    await ad.getByLabel("Entry academic year").selectOption({ label: `${s.yearName} · Aurora Institute of Technology` });
    expect(await seriousViolations(page)).toEqual([]);
    await ad.getByRole("button", { name: "Record admission" }).click();
    await expect(page.getByText(`ADM-${id}`)).toBeVisible();
    await expect(page.locator("header, main").getByText("Admitted").first()).toBeVisible();

    // initial enrolment → enrolled
    await page.getByRole("button", { name: "Enrol in a batch" }).click();
    const en = page.getByRole("dialog", { name: /Enrol Riya/ });
    await selectOptionContaining(en.getByLabel("Batch", { exact: true }), s.batchName);   // KI-81: by this run's label, not by position
    await en.getByRole("button", { name: "Initial enrolment" }).click();
    await expect(page.getByRole("table", { name: "Academic history" })).toContainText(s.batchName.slice(0, 10));
    expect(await seriousViolations(page)).toEqual([]);

    // lifecycle: mark active (reason required)
    await page.getByRole("button", { name: "More actions" }).click();
    await page.getByRole("menuitem", { name: "Mark active…" }).click();
    const lc = page.getByRole("dialog");
    await lc.getByRole("button", { name: "Mark active" }).click();
    await expect(lc.getByText("Give a reason.")).toBeVisible();
    await lc.getByLabel("Reason").fill("Classes started");
    await lc.getByRole("button", { name: "Mark active" }).click();
    await expect(page.getByText("Status: Active")).toBeVisible();

    // guardians
    await page.getByRole("tab", { name: /Guardians/ }).click();
    await page.getByRole("button", { name: "Add guardian" }).click();
    const gd = page.getByRole("dialog", { name: "Add guardian" });
    await gd.getByLabel("Name").fill("Sunil Verma");
    await gd.getByLabel("Relationship").selectOption("FATHER");
    await gd.getByLabel("Mobile").fill("+91 90000 54321");
    await gd.getByRole("button", { name: "Add" }).click();
    await expect(page.getByTestId("guardian-list")).toContainText("Sunil Verma");
    await expect(page.getByTestId("guardian-list")).toContainText("Primary");
    expect(await seriousViolations(page)).toEqual([]);

    // documents: uploads are quarantined and cannot be opened before a clean scan
    await page.getByRole("tab", { name: /Documents/ }).click();
    await page.getByRole("button", { name: "Upload" }).click();
    const up = page.getByRole("dialog", { name: "Upload a document" });
    await up.getByLabel("File").setInputFiles({ name: "id-proof.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\n%%EOF\n") });
    await up.getByRole("button", { name: "Upload" }).click();
    const docs = page.getByRole("table", { name: "Student documents" });
    await expect(docs).toContainText("Quarantined");
    await expect(docs.getByRole("button", { name: /is not available/ })).toBeDisabled();
    await expect(page.getByText(/cannot be opened until the malware scanner reports them clean/)).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    // history: every lifecycle change with reason and actor
    await page.getByRole("tab", { name: "History" }).click();
    const lifecycle = page.getByTestId("lifecycle-history");
    await expect(lifecycle).toContainText("Enrolled → Active");
    await expect(lifecycle).toContainText("Classes started");
    await expect(lifecycle).toContainText("Admitted → Enrolled");
    expect(await seriousViolations(page)).toEqual([]);

    // on-demand sign-in account (student role only)
    await page.goto(profile);
    await page.getByRole("button", { name: "Create sign-in account" }).click();
    const acct = page.getByRole("dialog", { name: /Create a sign-in account/ });
    await acct.getByLabel("Email address for the invitation").fill(`riya.${id.toLowerCase()}@students.aurora.test`);
    await acct.getByRole("button", { name: "Create and send invitation" }).click();
    await expect(page.getByText("Student role only.")).toBeVisible();
    await expect(page.getByText("Invitation pending")).toBeVisible();
  });

  test("search, filters and the lifecycle actions", async ({ page, request }) => {
    const id = stamp();
    const token = await apiToken(request, USERS.orgAdmin);
    const r = await request.post("/api/v1/students", { headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-s-${id}` },
      data: { institutionId: AIT, studentNo: `E2E-${id}`, firstName: "Kunal", lastName: `Search ${id}` } });
    expect(r.status()).toBe(201);
    await signIn(page, USERS.orgAdmin, "/students/list");
    await page.getByRole("searchbox", { name: "Search students" }).fill(`Search ${id}`);
    await expect(page).toHaveURL(new RegExp(`q=Search`));
    const table = page.getByRole("table", { name: "Students" });
    await expect(table.getByRole("link", { name: `Kunal Search ${id}` })).toBeVisible();
    await page.getByLabel("Status").selectOption("PROSPECTIVE");
    await expect(page).toHaveURL(/status=PROSPECTIVE/);
    await expect(table.getByRole("link", { name: `Kunal Search ${id}` })).toBeVisible();
    await page.getByRole("button", { name: "More filters" }).click();
    await expect(page.getByLabel("Department")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("searchbox", { name: "Search students" }).fill(`E2E-${id}`);
    await expect(table.getByRole("link", { name: `Kunal Search ${id}` })).toBeVisible();
    await table.getByRole("link", { name: `Kunal Search ${id}` }).click();
    // a prospective profile can only be cancelled (admitted/enrolled come from records)
    await page.getByRole("button", { name: "More actions" }).click();
    await expect(page.getByRole("menuitem", { name: "Mark active…" })).toHaveCount(0);
    await page.getByRole("menuitem", { name: "Cancel…" }).click();
    await page.getByRole("dialog").getByLabel("Reason").fill("Duplicate enquiry");
    await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).last().click();
    await expect(page.getByText("Status: Cancelled")).toBeVisible();
  });

  test("faculty see profiles without personal details, guardians or documents", async ({ page, request }) => {
    const id = stamp();
    const token = await apiToken(request, USERS.orgAdmin);
    const r = await request.post("/api/v1/students", { headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-f-${id}` },
      data: { institutionId: AIT, studentNo: `E2E-${id}`, firstName: "Faculty", lastName: `View ${id}`, personal: { mobile: "+91 90000 77777" } } });
    const sid = ((await r.json()) as { student: { id: string } }).student.id;
    await signIn(page, "arun.faculty@aurora.test", `/students/${sid}`);
    await expect(page.getByRole("heading", { name: `Faculty View ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByRole("tab", { name: /Guardians/ })).toHaveCount(0);
    await expect(page.getByRole("tab", { name: /Documents/ })).toHaveCount(0);
    await page.getByRole("tab", { name: "Personal information" }).click();
    await expect(page.getByText("Personal and contact details are restricted.")).toBeVisible();
    await expect(page.getByText("+91 90000 77777")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "More actions" })).toHaveCount(0);
  });

  test("student settings show institution numbering rules", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/students/settings");
    await expect(page.getByRole("heading", { name: "Student settings", level: 1 })).toBeVisible();
    await expect(page.getByRole("table", { name: "Numbering rules" })).toContainText("Aurora Institute of Technology");
    await expect(page.getByText("Transfer admission")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });

  for (const tag of ["@tablet", "@mobile"]) {
    test(`student screens fit without horizontal scrolling ${tag}`, async ({ page, request }) => {
      const id = stamp();
      const token = await apiToken(request, USERS.orgAdmin);
      const r = await request.post("/api/v1/students", { headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-r-${id}` },
        data: { institutionId: AIT, studentNo: `E2E-${id}`, firstName: "Responsive", lastName: `Check ${id}` } });
      const sid = ((await r.json()) as { student: { id: string } }).student.id;
      await signIn(page, USERS.orgAdmin, "/students");
      await expect(page.getByTestId("kpi-total")).toBeVisible();
      await noHorizontalScroll(page);
      for (const path of ["/students/list", "/students/new", "/students/settings", `/students/${sid}`, `/students/${sid}?tab=personal`,
        `/students/${sid}?tab=academic`, `/students/${sid}?tab=guardians`, `/students/${sid}?tab=documents`, `/students/${sid}?tab=history`]) {
        await page.goto(path);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await noHorizontalScroll(page);
      }
    });
  }
});
