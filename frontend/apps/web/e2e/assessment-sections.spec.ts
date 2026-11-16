import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

/**
 * Add-on C (Session 4): the author splits a paper into sections, places questions and rules in them, and states a
 * blueprint; validation shows the coverage proved for every paper and refuses one that some papers would miss; after an
 * independent review and publication the preview paper keeps the sections in order. Real Keycloak, API and database.
 */
const AUTHOR = USERS.orgAdmin;
const REVIEWER = USERS.instAdmin;
const PUBLISHER = "eshan.controller@aurora.test";
const FACULTY = "arun.faculty@aurora.test";
const COLLEGE = "0192a000-0000-7000-8000-00000000a101";
const RUN = Date.now().toString(36);
const TITLE = `Sectioned ${RUN}`;
const BANK = `E2E sections bank ${RUN}`;

const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });

async function headers(request: APIRequestContext, user: string) {
  return { Authorization: `Bearer ${await apiToken(request, user)}`, "Idempotency-Key": `e2e-${Math.random()}` };
}

async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

async function assessment(request: APIRequestContext): Promise<{ id: string }> {
  const r = await request.get(`/api/v1/assessments?q=${encodeURIComponent(TITLE)}`, { headers: await headers(request, AUTHOR) });
  const a = ((await r.json()) as { id: string; title: string }[]).find((x) => x.title === TITLE);
  expect(a, "assessment created in step 1").toBeTruthy();
  return a!;
}

test.describe.serial("assessment sections and blueprint (add-on C)", () => {
  test.beforeAll(async ({ request }) => {
    test.setTimeout(180_000);
    const tax = (await (await request.get("/api/v1/question-taxonomy", { headers: await headers(request, FACULTY) })).json()) as { difficulties: { id: string; label: string }[] };
    const [easy, , , hard] = tax.difficulties;
    const bank = await request.post("/api/v1/question-banks", { headers: await headers(request, AUTHOR), data: {
      orgUnitId: COLLEGE, code: `SB${RUN}`.slice(0, 40), name: BANK, intendedUses: ["ACADEMIC"],
      policy: { approvalPolicy: "STANDARD", requiredReviews: 1, approverDistinct: false } } });
    expect(bank.status()).toBe(201);
    const bankId = ((await bank.json()) as { id: string }).id;
    // three easy and four hard approved questions
    for (const [n, difficulty] of [easy, easy, easy, hard, hard, hard, hard].entries()) {
      const q = await request.post(`/api/v1/question-banks/${bankId}/questions`, { headers: await headers(request, FACULTY), data: {
        type: "SINGLE_CHOICE", content: content(`Sections ${RUN} ${difficulty!.label} question ${n + 1}`), marks: 2, difficultyId: difficulty!.id,
        body: { options: [{ id: "A", content: content("Option alpha") }, { id: "B", content: content("Option beta") }] },
        answerKey: { correct: "B" }, explanation: `Secret explanation ${RUN}` } });
      expect(q.status(), await q.text()).toBe(201);
      const id = ((await q.json()) as { id: string }).id;
      expect((await request.post(`/api/v1/questions/${id}/submit`, { headers: await headers(request, FACULTY), data: {} })).status()).toBe(200);
      expect((await request.post(`/api/v1/questions/${id}/reviews`, { headers: await headers(request, REVIEWER),
        data: { decision: "REVIEWED", checklist: { accurate: true } } })).status()).toBe(200);
      expect((await request.post(`/api/v1/questions/${id}/approve`, { headers: await headers(request, PUBLISHER), data: { reason: "E2E" } })).status()).toBe(200);
    }
  });

  test("1 · the author builds two sections and a blueprint; validation proves it", async ({ page, request }) => {
    const tax = (await (await request.get("/api/v1/question-taxonomy", { headers: await headers(request, FACULTY) })).json()) as { difficulties: { id: string; label: string }[] };
    const easy = tax.difficulties[0]!.label;
    const hard = tax.difficulties[3]!.label;
    await signIn(page, AUTHOR, "/assessments");
    await page.getByRole("button", { name: "New assessment" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Purpose").selectOption("INTERNAL");
    await dialog.getByLabel("Institution").selectOption(COLLEGE);
    await dialog.getByLabel("Code").fill(`SC-${RUN}`);
    await dialog.getByLabel("Title").fill(TITLE);
    await dialog.getByRole("button", { name: "Create draft" }).click();
    await expect(page.getByRole("heading", { name: new RegExp(`Edit SC-${RUN}`) })).toBeVisible();

    // two sections
    await page.getByRole("button", { name: "Add section" }).click();
    await page.getByLabel("Section 1 title").fill("Part A — Foundations");
    await page.getByLabel("Section 1 instructions").fill("Answer every question in this part.");
    await page.getByRole("button", { name: "Add section" }).click();
    await page.getByLabel("Section 2 title").fill("Part B — Problems");

    // Part A: one rule over easy questions; Part B: one rule over hard questions
    for (const [n, label, difficulty, section] of [[1, "Easy", easy, "1. Part A — Foundations"], [2, "Hard", hard, "2. Part B — Problems"]] as const) {
      await page.getByRole("button", { name: "Add rule" }).click();
      await page.getByLabel("Label").nth(n - 1).fill(label);
      await page.getByLabel("Bank").nth(n - 1).selectOption({ label: BANK });
      await page.getByLabel("Difficulty").nth(n - 1).selectOption({ label: difficulty });
      await page.getByLabel("Draw").nth(n - 1).fill("2");
      await page.getByLabel(`Section of rule ${n}`).selectOption({ label: section });
    }
    // blueprint: at least 2 hard questions in Part B
    await page.getByRole("button", { name: "Add constraint" }).click();
    await page.getByLabel("Scope of constraint 1").selectOption({ label: "Section 2" });
    await page.getByLabel("Dimension of constraint 1").selectOption("DIFFICULTY");
    await page.getByLabel("Target of constraint 1").selectOption({ label: hard });
    await page.getByLabel("At least").fill("2");
    await axe(page);
    await page.getByRole("button", { name: "Save draft" }).click();

    const coverage = page.getByRole("list", { name: "Blueprint coverage" });
    await expect(coverage.getByText("Guaranteed", { exact: true })).toBeVisible();
    await expect(coverage.getByText(/every paper: 2–2 questions/)).toBeVisible();
    await expect(page.getByText("Ready to submit")).toBeVisible();
    await expect(page.getByRole("list", { name: "Sections" }).getByText("1. Part A — Foundations")).toBeVisible();
    await axe(page);
  });

  test("2 · a blueprint some papers would miss is shown and blocks submission", async ({ page, request }) => {
    const { id } = await assessment(request);
    await signIn(page, AUTHOR, `/assessments/${id}/edit`);
    // require a hard question in Part A, which draws only easy questions
    await page.getByRole("button", { name: "Add constraint" }).click();
    await page.getByLabel("Scope of constraint 2").selectOption({ label: "Section 1" });
    await page.getByLabel("Dimension of constraint 2").selectOption("DIFFICULTY");
    const tax = (await (await request.get("/api/v1/question-taxonomy", { headers: await headers(request, FACULTY) })).json()) as { difficulties: { label: string }[] };
    await page.getByLabel("Target of constraint 2").selectOption({ label: tax.difficulties[3]!.label });
    await page.getByLabel("At least").nth(1).fill("1");
    await page.getByRole("button", { name: "Save draft" }).click();
    const coverage = page.getByRole("list", { name: "Blueprint coverage" });
    await expect(coverage.getByText("Not guaranteed")).toBeVisible();
    await expect(page.getByText(/at least 1 required, but a paper can contain as few as 0/).first()).toBeVisible();
    await expect(page.getByText("1 to fix")).toBeVisible();
    // the author removes it again
    await page.goto(`/assessments/${id}/edit`);
    await page.getByRole("button", { name: "Remove constraint 2" }).click();
    await page.getByRole("button", { name: "Save draft" }).click();
    await expect(page.getByText("Ready to submit")).toBeVisible();
  });

  test("3 · after review and publication, the preview paper keeps the sections in order", async ({ page, request }) => {
    const { id } = await assessment(request);
    expect((await request.post(`/api/v1/assessments/${id}/submit`, { headers: await headers(request, AUTHOR), data: {} })).status()).toBe(200);
    expect((await request.post(`/api/v1/assessments/${id}/reviews`, { headers: await headers(request, REVIEWER), data: { decision: "REVIEWED" } })).status()).toBe(200);
    expect((await request.post(`/api/v1/assessments/${id}/publish`, { headers: await headers(request, PUBLISHER), data: { reason: "E2E sections" } })).status()).toBe(200);
    const bodies: string[] = [];
    page.on("response", (r) => { if (r.url().includes("/api/")) void r.text().then((t) => bodies.push(t)).catch(() => undefined); });
    await signIn(page, AUTHOR, `/assessments/${id}`);
    await page.getByRole("button", { name: "Preview a paper" }).click();
    await expect(page.getByRole("heading", { name: new RegExp(`Paper preview — ${TITLE}`) })).toBeVisible();
    const headings = page.getByRole("heading", { name: /^Section \d: / });
    await expect(headings).toHaveText(["Section 1: Part A — Foundations", "Section 2: Part B — Problems"]);
    await expect(page.getByText("Answer every question in this part.")).toBeVisible();
    await expect(page.getByRole("heading", { name: /^Question \d$/ })).toHaveCount(4);
    await page.getByRole("button", { name: "Reproduce from seed" }).click();
    await expect(page.getByText("The stored seed reproduces this paper exactly.")).toBeVisible();
    await axe(page);
    const all = bodies.join("\n");
    expect(all).not.toContain(`Secret explanation ${RUN}`);
    expect(all).not.toContain("answerKey");
    expect(all).not.toContain('"correct"');
  });
});
