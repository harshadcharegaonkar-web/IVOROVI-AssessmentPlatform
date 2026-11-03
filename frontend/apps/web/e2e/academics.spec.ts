import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

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

/** A free year (no overlap with earlier runs' AIT academic years): one after the latest existing end. */
async function freeAcademicYear(request: APIRequestContext): Promise<number> {
  const headers = { Authorization: `Bearer ${await apiToken(request, USERS.orgAdmin)}` };
  const res = await request.get(`/api/v1/academics/academic-years?ownerOrgUnitId=${AIT}&size=100&sort=startDate&direction=desc`, { headers });
  expect(res.ok()).toBeTruthy();
  const items = ((await res.json()) as { items: { endDate: string }[] }).items;
  const latest = items.length > 0 ? Number(items[0]!.endDate.slice(0, 4)) : 2040;
  return Math.max(2040, latest + 1);
}

/** A teaching staff profile of AIT's CSE department, created through the real API (the 1.2 staff model). */
async function teachingStaff(request: APIRequestContext, id: string): Promise<string> {
  const headers = { Authorization: `Bearer ${await apiToken(request, USERS.orgAdmin)}`, "Idempotency-Key": `e2e-${id}` };
  const res = await request.post("/api/v1/staff", { headers, data: { staffNo: `E2E-${id}`, givenName: "Priya", familyName: `Teacher ${id}`, category: "FACULTY",
    designation: "Assistant Professor", orgUnitId: CSE } });
  expect(res.status(), await res.text()).toBe(201);
  return `Priya Teacher ${id}`;
}

test.describe("Increment 1.4: academic structure", () => {
  test("program → course → subject → calendar → batch → offering → faculty", async ({ page, request }) => {
    const id = stamp();
    const year = await freeAcademicYear(request);
    const teacher = await teachingStaff(request, id);
    await signIn(page, USERS.orgAdmin, "/academics");
    await expect(page.getByRole("heading", { name: "Academics", level: 1 })).toBeVisible();
    await expect(page.getByTestId("kpi-programs")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    // program
    await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Programs" }).click();
    await page.getByRole("link", { name: "New program" }).first().click();
    await page.getByLabel("Code").fill(`E2E${id}`);
    await page.getByLabel("Name", { exact: true }).fill(`E2E B.Tech ${id}`);
    await page.getByLabel("Owned by").selectOption(AIT);
    await page.getByLabel("Duration (years)").fill("4");
    await expect(page.getByText("Leave empty to use 8")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("button", { name: "Create program" }).click();
    await expect(page.getByRole("heading", { name: `E2E B.Tech ${id}`, level: 1 })).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
    const programUrl = page.url();

    // duplicate code is refused with a field message
    await page.goto("/academics/programs/new");
    await page.getByLabel("Code").fill(`e2e${id}`);
    await page.getByLabel("Name", { exact: true }).fill("Duplicate");
    await page.getByLabel("Owned by").selectOption(AIT);
    await page.getByRole("button", { name: "Create program" }).click();
    await expect(page.getByText(/already uses the code/)).toBeVisible();
    await page.goto(programUrl);
    await expect(page.getByRole("heading", { name: `E2E B.Tech ${id}`, level: 1 })).toBeVisible();

    // course
    await page.getByRole("link", { name: "Add course" }).click();
    await page.getByLabel("Department").selectOption(CSE);
    await page.getByLabel("Code").fill("CSE");
    await page.getByLabel("Name", { exact: true }).fill(`E2E Computer Science ${id}`);
    await page.getByRole("button", { name: "Create course" }).click();
    await expect(page.getByRole("heading", { name: `E2E Computer Science ${id}`, level: 1 })).toBeVisible();
    const courseUrl = page.url();

    // subject master
    await page.goto("/academics/subjects/new");
    await page.getByLabel("Code").fill(`DS${id}`);
    await page.getByLabel("Name", { exact: true }).fill(`E2E Data Structures ${id}`);
    await page.getByLabel("Credits").fill("4");
    await page.getByLabel("Owned by").selectOption(AIT);
    expect(await seriousViolations(page)).toEqual([]);
    await page.getByRole("button", { name: "Create subject" }).click();
    await expect(page.getByRole("heading", { name: `DS${id} · E2E Data Structures ${id}`, level: 1 })).toBeVisible();

    // curriculum: semester 3 of the course
    await page.goto(courseUrl);
    await page.getByRole("button", { name: "Add subject to Semester 3" }).click();
    const add = page.getByRole("dialog", { name: "Add a subject to the curriculum" });
    await add.getByLabel("Find a subject").fill(`DS${id}`);
    await expect(add.getByLabel("Subject", { exact: true }).locator("option", { hasText: `DS${id}` })).toHaveCount(1);
    await add.getByLabel("Subject", { exact: true }).selectOption({ index: 1 });
    await add.getByRole("button", { name: "Add" }).click();
    await expect(page.getByTestId("curriculum-term-3")).toContainText(`DS${id}`);
    expect(await seriousViolations(page)).toEqual([]);

    // academic calendar: year owned by AIT, one semester, activate both
    await page.goto("/academics/calendar");
    await page.getByRole("button", { name: "New academic year" }).first().click();
    const yd = page.getByRole("dialog", { name: "New academic year" });
    await yd.getByLabel("Code").fill(`AY${year}-${id}`.slice(0, 30));
    await yd.getByLabel("Name").fill(`${year}–${(year + 1) % 100} E2E`);
    await yd.getByLabel("Applies to").selectOption(AIT);
    await yd.getByLabel("Starts on").fill(`${year}-07-01`);
    await yd.getByLabel("Ends on").fill(`${year + 1}-06-30`);
    await yd.getByRole("button", { name: "Create" }).click();
    await expect(page.getByRole("heading", { name: `${year}–${(year + 1) % 100} E2E`, level: 1 })).toBeVisible();
    const yearId = page.url().split("/").pop()!;
    await page.getByRole("button", { name: "Add semester" }).click();
    const td = page.getByRole("dialog", { name: /Add a semester/ });
    await td.getByLabel("Starts on").fill(`${year}-07-01`);
    await td.getByLabel("Ends on").fill(`${year}-12-15`);
    await td.getByLabel("Examinations start (optional)").fill(`${year}-12-20`);
    await td.getByLabel("Examinations end").fill(`${year}-12-24`);
    await td.getByRole("button", { name: "Add" }).click();
    await expect(td.getByText("Must fall within the term").first()).toBeVisible();          // client-side window check
    await td.getByLabel("Examinations start (optional)").fill(`${year}-12-01`);
    await td.getByLabel("Examinations end").fill(`${year}-12-10`);
    await td.getByRole("button", { name: "Add" }).click();
    await expect(page.getByRole("table", { name: "Semesters" })).toContainText("Semester 1");
    await page.getByRole("button", { name: "Activate" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Activate" }).click();
    await expect(page.getByRole("heading", { level: 1 }).locator("..")).toContainText("Active");
    await page.getByRole("button", { name: "Actions for Semester 1" }).click();
    await page.getByRole("menuitem", { name: "Mark active" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Activate" }).click();
    await expect(page.getByRole("table", { name: "Semesters" }).getByText("Active")).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);

    // batch of the course
    await page.goto(courseUrl);
    await page.getByRole("link", { name: "New batch" }).click();
    await page.getByLabel("Academic year").selectOption(yearId);
    await page.getByLabel("Code").fill(`CSE-${year}-A`);
    await page.getByLabel("Name", { exact: true }).fill(`E2E CSE ${year} Section A ${id}`);
    await page.getByLabel("Admission year").fill(String(year));
    await page.getByLabel("Section (optional)").fill("A");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.getByRole("heading", { name: `E2E CSE ${year} Section A ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByText(String(year + 4), { exact: true })).toBeVisible();      // default graduation = admission + duration

    // offering from the curriculum (semester 3 of the course in the calendar's semester 1)
    await page.getByRole("button", { name: "Offer a subject" }).click();
    const od = page.getByRole("dialog", { name: /Offer a subject/ });
    await od.getByLabel("Calendar semester").selectOption({ index: 1 });
    await od.getByLabel("Course semester").selectOption("3");
    await od.getByLabel("Subject", { exact: true }).selectOption({ index: 1 });
    await od.getByRole("button", { name: "Offer" }).click();
    await expect(page.getByRole("heading", { name: `DS${id} · E2E Data Structures ${id}`, level: 1 })).toBeVisible();
    await expect(page.getByText("No faculty assigned")).toBeVisible();

    // faculty assignment from the existing staff model
    await page.getByRole("button", { name: "Assign faculty" }).click();
    const ad = page.getByRole("dialog", { name: "Assign faculty" });
    await ad.getByLabel("Find staff").fill(`Teacher ${id}`);
    await ad.getByLabel("Staff member").selectOption({ index: 1 });
    await ad.getByRole("button", { name: "Assign" }).click();
    const faculty = page.getByRole("table", { name: "Faculty assignments" });
    await expect(faculty).toContainText(teacher);
    await expect(faculty).toContainText("Primary");
    expect(await seriousViolations(page)).toEqual([]);

    // end it with a reason: history is kept
    await page.getByRole("button", { name: `Actions for ${teacher}` }).click();
    await page.getByRole("menuitem", { name: "End assignment…" }).click();
    await page.getByRole("dialog").getByLabel("Reason").fill("Moved to another section");
    await page.getByRole("dialog").getByRole("button", { name: "End assignment" }).click();
    await expect(page.getByText("No faculty assigned")).toBeVisible();
    await page.getByRole("switch", { name: "Show ended assignments" }).click();
    await expect(faculty).toContainText("Moved to another section");

    // offerings list, tree and dashboard reflect the real data
    await page.goto(`/academics/offerings?q=DS${id}`);
    await expect(page.getByRole("table", { name: "Subject offerings" })).toContainText("Not assigned");
    expect(await seriousViolations(page)).toEqual([]);
    await page.goto("/academics/structure");
    await expect(page.getByRole("tree", { name: "Academic structure" })).toContainText(`E2E B.Tech ${id}`);
    expect(await seriousViolations(page)).toEqual([]);
    await page.goto("/academics");
    await expect(page.getByTestId("kpi-offerings")).toHaveText(/^[1-9][0-9,]*$/);
    await expect(page.getByRole("link", { name: /Subject offerings/ })).toContainText("without faculty");
  });

  test("lists filter, sort and paginate; the semester screen spans years", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/academics/programs");
    await page.getByLabel("Level").selectOption("UNDERGRADUATE");
    await expect(page).toHaveURL(/level=UNDERGRADUATE/);
    await page.getByRole("button", { name: /^Level/ }).click();
    await expect(page).toHaveURL(/sort=level/);
    for (const path of ["/academics/courses", "/academics/subjects", "/academics/batches", "/academics/terms", "/academics/calendar", "/academics/offerings"]) {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await expect(page.getByRole("table")).toBeVisible();
      expect(await seriousViolations(page), path).toEqual([]);
    }
  });

  test("a college administrator works only inside their college", async ({ page }) => {
    await signIn(page, USERS.instAdmin, "/academics/programs/new");
    const owner = page.getByLabel("Owned by");
    await expect(owner.locator("option", { hasText: "Aurora Institute of Technology" })).toHaveCount(1);
    await expect(owner.locator("option", { hasText: "Aurora University" })).toHaveCount(0);
    await expect(owner.locator("option", { hasText: "Commerce" })).toHaveCount(0);
    await page.goto("/academics/calendar");
    await page.getByRole("button", { name: "New academic year" }).first().click();
    await expect(page.getByRole("dialog").getByLabel("Applies to").locator("option", { hasText: "Whole organization" })).toHaveCount(0);
  });

  test("academic terminology is configurable without changing stored types", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/settings/system");
    const singular = page.getByTestId("label-TERM-singular");
    const plural = page.getByTestId("label-TERM-plural");
    await singular.fill("Trimester");
    await plural.fill("Trimesters");
    await page.getByRole("button", { name: "Save settings" }).click();
    await expect(page.getByText("Settings saved")).toBeVisible();
    try {
      await page.goto("/academics/terms");
      await expect(page.getByRole("heading", { name: "Trimesters", level: 1 })).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Trimesters" })).toBeVisible();
    } finally {
      await page.goto("/settings/system");
      await page.getByTestId("label-TERM-singular").fill("Semester");
      await page.getByTestId("label-TERM-plural").fill("Semesters");
      await page.getByRole("button", { name: "Save settings" }).click();
      await expect(page.getByText("Settings saved")).toBeVisible();
    }
  });

  for (const tag of ["@tablet", "@mobile"]) {
    test(`academic screens fit without horizontal scrolling ${tag}`, async ({ page }) => {
      await signIn(page, USERS.orgAdmin, "/academics");
      await expect(page.getByTestId("kpi-programs")).toBeVisible();
      await noHorizontalScroll(page);
      for (const path of ["/academics/programs", "/academics/courses", "/academics/subjects", "/academics/calendar", "/academics/terms",
        "/academics/batches", "/academics/offerings", "/academics/structure", "/academics/programs/new", "/academics/batches/new"]) {
        await page.goto(path);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await noHorizontalScroll(page);
      }
    });
  }
});
