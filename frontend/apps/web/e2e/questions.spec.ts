import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

/**
 * Increment 2.1 demonstration (items 1–7, 9–11; item 8 — preserved referenced versions — is enforced by the database and
 * shown in QuestionLifecycleIT, as assessments arrive in 2.2). Real Keycloak, API and database; synthetic data only.
 */
const FACULTY = "arun.faculty@aurora.test";
const CONTROLLER = "eshan.controller@aurora.test";
const COLLEGE = "0192a000-0000-7000-8000-00000000a101";
const RUN = Date.now().toString(36);
const BANK = `E2E Physics ${RUN}`;
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=", "base64");

const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });

async function api(request: APIRequestContext, user: string) {
  const token = await apiToken(request, user);
  return { Authorization: `Bearer ${token}`, "Idempotency-Key": `e2e-${Math.random()}` };
}

async function bankId(request: APIRequestContext): Promise<string> {
  const list = await request.get("/api/v1/question-banks", { headers: await api(request, FACULTY) });
  const bank = ((await list.json()) as { id: string; name: string }[]).find((b) => b.name === BANK);
  expect(bank, "bank created in step 1").toBeTruthy();
  return bank!.id;
}

async function questionByText(request: APIRequestContext, bank: string, text: string): Promise<string> {
  const r = await request.get(`/api/v1/question-banks/${bank}/questions?q=${encodeURIComponent(text)}`, { headers: await api(request, FACULTY) });
  return ((await r.json()) as { items: { id: string }[] }).items[0]!.id;
}

async function openQuestion(page: Page, request: APIRequestContext, text: string) {
  await page.goto(`/questions/items/${await questionByText(request, await bankId(request), text)}`);
}

test.describe.serial("question bank lifecycle", () => {
  test("1 · an administrator creates an institution-scoped bank", async ({ page }) => {
    await signIn(page, USERS.orgAdmin, "/questions");
    await page.getByRole("button", { name: "New bank" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Institution").selectOption(COLLEGE);
    await dialog.getByLabel("Code").fill(`PHY${RUN}`.slice(0, 40));
    await dialog.getByLabel("Name").fill(BANK);
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("link", { name: BANK })).toBeVisible();
  });

  test("2–4 · an author adds an image and writes questions of every type, including mathematics", async ({ page, request }) => {
    const bank = await bankId(request);
    await signIn(page, FACULTY, `/questions/banks/${bank}/assets`);
    await page.getByLabel("Description (alternative text)").fill("Velocity–time graph");
    await page.getByLabel("Image file").setInputFiles({ name: "graph.png", mimeType: "image/png", buffer: PNG });
    await expect(page.getByRole("cell", { name: /graph\.png/ })).toBeVisible();

    await page.goto(`/questions/banks/${bank}/new`);
    await page.getByLabel("Statement").fill(`Kinematics ${RUN}: a body falls for $t = 2\\,\\text{s}$. Which expression gives its speed?\n\n$$v = \\int_0^t g\\,d\\tau$$`);
    await page.getByLabel("Image", { exact: true }).selectOption({ label: "graph.png (1×1)" });
    await page.getByRole("button", { name: "Add", exact: true }).click();
    await page.getByLabel("Option A", { exact: true }).fill("$g t$");
    await page.getByLabel("Option B", { exact: true }).fill("$\\frac{1}{2} g t^2$");
    await page.getByLabel("Option C", { exact: true }).fill("$\\sqrt{2 g t}$");
    await page.getByLabel("Option D", { exact: true }).fill("$g / t$");
    await page.getByLabel("Option A is correct").check();
    await expect(page.getByTestId("question-preview").locator("math").first()).toBeVisible();
    await page.getByRole("button", { name: "Save draft" }).click();
    await expect(page.getByText("Question · version 1")).toBeVisible();
    await expect(page.locator("math").first()).toBeVisible();
    await expect(page.getByRole("img", { name: "Velocity–time graph" })).toBeVisible();

    // the other types through the API (same validation as the editor)
    const headers = await api(request, FACULTY);
    for (const draft of [
      { type: "TRUE_FALSE", content: content(`Light travels faster than sound ${RUN}.`), body: {}, answerKey: { value: true } },
      { type: "SHORT_ANSWER", content: content(`Name the SI unit of force ${RUN}.`), body: { maxLength: 40 }, answerKey: { answers: [{ text: "newton" }], match: "NORMALIZED" } },
      { type: "NUMERIC", content: content(`Speed after 2 s of free fall ${RUN} (g = 9.81)?`), body: { precision: 2, unit: "m/s" },
        answerKey: { value: "19.62", tolerance: { type: "ABSOLUTE", value: "0.05" } }, marks: 2 },
    ]) {
      const r = await request.post(`/api/v1/question-banks/${bank}/questions`, { headers, data: draft });
      expect(r.status(), draft.type).toBe(201);
    }
  });

  test("5–6 · submission, independent review and approval", async ({ page, request }) => {
    await signIn(page, FACULTY);
    await openQuestion(page, request, `Kinematics ${RUN}`);
    await page.getByRole("button", { name: "Submit for review" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Submit for review" }).click();
    await expect(page.getByText("In review").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Review", exact: true })).toHaveCount(0);   // never one's own

    await page.context().clearCookies();
    await signIn(page, USERS.instAdmin, "/questions/review");
    await page.getByRole("link", { name: new RegExp(`Kinematics ${RUN}`) }).click();
    await page.getByRole("button", { name: "Review", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Review" }).click();
    await expect(page.getByText("Reviewed").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);              // reviewers do not approve

    await page.context().clearCookies();
    await signIn(page, CONTROLLER, "/questions/review");
    await page.getByRole("tab", { name: "To approve" }).click();
    await page.getByRole("link", { name: new RegExp(`Kinematics ${RUN}`) }).click();
    await page.getByRole("button", { name: "Approve" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Approve" }).click();
    await expect(page.getByText("Approved").first()).toBeVisible();
  });

  test("7 · a new version never changes the approved one", async ({ page, request }) => {
    await signIn(page, FACULTY);
    await openQuestion(page, request, `Kinematics ${RUN}`);
    await page.getByRole("button", { name: "New version" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "New version" }).click();
    await expect(page.getByText("Question · version 2")).toBeVisible();
    await page.getByRole("link", { name: "Edit" }).click();
    await page.getByLabel("Statement").fill(`Kinematics ${RUN} (revised): which expression gives the speed after $t$ seconds?`);
    await page.getByRole("button", { name: "Save draft" }).click();
    await expect(page.getByText("Approved version 1 (unchanged)")).toBeVisible();
    await expect(page.getByText(/a body falls for/)).toBeVisible();
  });

  test("9 · answer keys only for permitted staff", async ({ page, request }) => {
    const id = await questionByText(request, await bankId(request), `Kinematics ${RUN}`);
    const key = (user: string) => api(request, user).then((h) => request.get(`/api/v1/questions/${id}/versions/1/answer-key`, { headers: h }));
    expect((await key(FACULTY)).status(), "author after approval").toBe(403);
    expect((await key(USERS.orgAdmin)).status(), "administrator").toBe(403);
    expect((await key(USERS.otherTenantAdmin)).status(), "other organization").toBe(404);
    const ok = await key(CONTROLLER);
    expect(ok.status()).toBe(200);
    expect(ok.headers()["cache-control"]).toBe("no-store");
    const detail = await request.get(`/api/v1/questions/${id}`, { headers: await api(request, CONTROLLER) });
    expect(JSON.stringify(await detail.json())).not.toContain("\"correct\"");
    // in the UI the key is revealed only on request, and the reveal is audited
    await signIn(page, CONTROLLER, `/questions/items/${id}?v=1`);
    await page.getByRole("button", { name: /Show answer key/ }).click();
    await expect(page.getByTestId("answer-key")).toContainText("\"correct\"");
  });

  test("10 · import with duplicate detection", async ({ page, request }) => {
    const bank = await bankId(request);
    const csv = "type,statement,correct,accepted_answers\n"
      + `TRUE_FALSE,Light travels faster than sound ${RUN}.,TRUE,\n`
      + `TRUE_FALSE,Sound needs a medium ${RUN}.,TRUE,\n`
      + `TRUE_FALSE,Sound needs a medium ${RUN}.,TRUE,\n`
      + `SHORT_ANSWER,Click $\\href{javascript:alert(1)}{here}$,,x\n`;
    await signIn(page, FACULTY, `/questions/banks/${bank}/import`);
    await page.getByLabel("Import file").setInputFiles({ name: "questions.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
    const summary = page.getByTestId("import-summary");
    await expect(summary).toContainText("Rows4");
    await expect(page.getByText("Duplicate in bank")).toBeVisible();
    await expect(page.getByText("Duplicate in file")).toBeVisible();
    await expect(page.getByText(/command that is not allowed/)).toBeVisible();
    await page.getByRole("button", { name: "Create drafts" }).click();
    await expect(page.getByText("1 draft question(s) created")).toBeVisible();
  });

  test("11 · other organizations and roles without access see nothing", async ({ request }) => {
    const bank = await bankId(request);
    const other = await request.get(`/api/v1/question-banks/${bank}`, { headers: await api(request, USERS.otherTenantAdmin) });
    expect(other.status()).toBe(404);
    const student = await request.get(`/api/v1/question-banks/${bank}/questions`, { headers: await api(request, USERS.student) });
    expect(student.status()).toBe(403);
    const anonymous = await request.get(`/api/v1/question-banks/${bank}`);
    expect(anonymous.status()).toBe(401);
  });
});

/** Mathematical notation (L-2.1-1): MathML only, every expression family, accessibility. Runs in every browser project. */
const EXPRESSIONS: [string, string][] = [
  ["fraction", "\\frac{a+b}{c-d}"],
  ["matrix", "\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}"],
  ["integral", "\\int_0^{\\pi} \\sin x \\, dx = 2"],
  ["summation", "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}"],
  ["limit", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1"],
  ["derivative", "\\frac{d}{dx}\\left(x^3\\right) = 3x^2"],
  ["sub- and superscripts", "a_{i,j}^{2} + x_1^{n-1}"],
  ["complex", "\\left( \\sum_{k=0}^{\\infty} \\frac{(-1)^k x^{2k+1}}{(2k+1)!} \\right)^2 + \\sqrt[3]{\\frac{\\alpha}{\\beta}} \\le \\oint_C \\vec{F} \\cdot d\\vec{r}"],
];

test("@math every expression family renders as accessible MathML, text stays text", async ({ page, request }) => {
  const headers = await api(request, FACULTY);
  const banks = (await (await request.get("/api/v1/question-banks", { headers })).json()) as { id: string; canAuthor: boolean }[];
  const bank = banks.find((b) => b.canAuthor)!.id;
  const blocks = [
    { type: "paragraph", runs: [{ text: `Math rendering check ${Date.now()} <img src=x onerror=alert(1)><script>alert(1)</script>` }] },
    ...EXPRESSIONS.map(([, tex]) => ({ type: "math", tex })),
  ];
  const created = await request.post(`/api/v1/question-banks/${bank}/questions`, { headers,
    data: { type: "TRUE_FALSE", content: { format: "ivorovi.content.v1", blocks }, body: {}, answerKey: { value: true } } });
  expect(created.status()).toBe(201);
  const id = ((await created.json()) as { id: string }).id;
  // injection is refused by the server before anything is stored
  const bad = await request.post(`/api/v1/question-banks/${bank}/questions`, { headers,
    data: { type: "TRUE_FALSE", content: { format: "ivorovi.content.v1", blocks: [{ type: "math", tex: "\\href{javascript:alert(1)}{x}" }] }, body: {}, answerKey: { value: true } } });
  expect(bad.status()).toBe(400);

  let dialogs = 0;
  page.on("dialog", (d) => { dialogs++; void d.dismiss(); });
  await signIn(page, FACULTY, `/questions/items/${id}`);
  const rendered = page.locator("[data-math='rendered']");
  await expect(rendered).toHaveCount(EXPRESSIONS.length);
  await expect(page.locator("[data-math='fallback']")).toHaveCount(0);
  for (const [name, tex] of EXPRESSIONS) {
    await expect(page.locator("annotation", { hasText: tex }).first(), name).toBeAttached();
  }
  expect(await page.locator("math [style], math [href], math [onclick], .katex-html").count()).toBe(0);
  await expect(page.getByText("<script>alert(1)</script>", { exact: false })).toBeVisible();
  expect(dialogs, "no script ran").toBe(0);
  // each formula is exposed to assistive technology as math with its TeX annotation
  const snapshot = await page.locator("main").ariaSnapshot();
  expect(snapshot).toContain("math");
  const axe = await new AxeBuilder({ page }).include("main").analyze();
  expect(axe.violations.filter((v) => v.impact === "serious" || v.impact === "critical"), JSON.stringify(axe.violations.map((v) => v.id))).toEqual([]);
  await page.screenshot({ path: `test-results/math-${test.info().project.name}.png`, fullPage: true });
});
