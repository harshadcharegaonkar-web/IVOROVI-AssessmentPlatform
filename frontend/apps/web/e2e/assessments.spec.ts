import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { apiToken, signIn, USERS } from "./support";

/**
 * Increment 2.2 / M1 — Journey 1: an assessment is authored, independently reviewed and published by three different
 * people (high-stakes), and a paper is previewed and reproduced from its seed. Real Keycloak, API and database; synthetic data.
 */
const AUTHOR = USERS.orgAdmin;                      // holds assessment.author
const REVIEWER = USERS.instAdmin;                   // holds assessment.review (institution)
const PUBLISHER = "eshan.controller@aurora.test";   // holds assessment.publish (privileged)
const FACULTY = "arun.faculty@aurora.test";
const COLLEGE = "0192a000-0000-7000-8000-00000000a101";
const RUN = Date.now().toString(36);
const TITLE = `Mid-term ${RUN}`;

const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });

async function headers(request: APIRequestContext, user: string) {
  return { Authorization: `Bearer ${await apiToken(request, user)}`, "Idempotency-Key": `e2e-${Math.random()}` };
}

async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

/** Records every API response body the browser receives, to prove no answer information reaches the client. */
function recordApi(page: Page): string[] {
  const bodies: string[] = [];
  page.on("response", (r) => {
    if (!r.url().includes("/api/")) return;
    void r.text().then((t) => bodies.push(t)).catch(() => undefined);
  });
  return bodies;
}

function expectNoAnswers(bodies: string[]) {
  expect(bodies.length).toBeGreaterThan(0);
  const all = bodies.join("\n");
  expect(all).not.toContain(`Secret explanation ${RUN}`);
  expect(all).not.toContain("answerKey");
  expect(all).not.toContain('"correct"');
}

async function assessmentId(request: APIRequestContext): Promise<string> {
  const r = await request.get(`/api/v1/assessments?q=${encodeURIComponent(TITLE)}`, { headers: await headers(request, AUTHOR) });
  const a = ((await r.json()) as { id: string; title: string }[]).find((x) => x.title === TITLE);
  expect(a, "assessment created in step 1").toBeTruthy();
  return a!.id;
}

test.describe.serial("assessment authoring, review and publication (M1)", () => {
  test.beforeAll(async ({ request }) => {
    // six approved questions in a fresh bank: author (faculty) → review (institution admin) → approval (exam controller)
    const bank = await request.post("/api/v1/question-banks", { headers: await headers(request, AUTHOR), data: {
      orgUnitId: COLLEGE, code: `AS${RUN}`.slice(0, 40), name: `E2E Assessment bank ${RUN}`, intendedUses: ["ACADEMIC"],
      policy: { approvalPolicy: "STANDARD", requiredReviews: 1, approverDistinct: false } } });
    expect(bank.status()).toBe(201);
    const bankId = ((await bank.json()) as { id: string }).id;
    for (let i = 1; i <= 6; i++) {
      const q = await request.post(`/api/v1/question-banks/${bankId}/questions`, { headers: await headers(request, FACULTY), data: {
        type: "SINGLE_CHOICE", content: content(`Assessment ${RUN} question ${i}: which option is right?`), marks: 2, negativeMarks: 0.5,
        body: { options: [{ id: "A", content: content("Option alpha") }, { id: "B", content: content("Option beta") }, { id: "C", content: content("Option gamma") }] },
        answerKey: { correct: "B" }, explanation: `Secret explanation ${RUN}` } });
      expect(q.status(), await q.text()).toBe(201);
      const id = ((await q.json()) as { id: string }).id;
      expect((await request.post(`/api/v1/questions/${id}/submit`, { headers: await headers(request, FACULTY), data: {} })).status()).toBe(200);
      expect((await request.post(`/api/v1/questions/${id}/reviews`, { headers: await headers(request, REVIEWER),
        data: { decision: "REVIEWED", checklist: { accurate: true } } })).status()).toBe(200);
      expect((await request.post(`/api/v1/questions/${id}/approve`, { headers: await headers(request, PUBLISHER), data: { reason: "E2E" } })).status()).toBe(200);
    }
  });

  test("1 · the author creates a semester examination from approved questions and a random rule", async ({ page }) => {
    const bodies = recordApi(page);
    await signIn(page, AUTHOR, "/assessments");
    await page.getByRole("button", { name: "New assessment" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Purpose").selectOption("SEMESTER");
    await expect(dialog.getByText("three different people")).toBeVisible();
    await dialog.getByLabel("Institution").selectOption(COLLEGE);
    await dialog.getByLabel("Code").fill(`MT-${RUN}`);
    await dialog.getByLabel("Title").fill(TITLE);
    await dialog.getByRole("button", { name: "Create draft" }).click();

    await expect(page.getByRole("heading", { name: new RegExp(`Edit MT-${RUN}`) })).toBeVisible();
    await page.getByLabel("Instructions for candidates").fill("Answer every question. Calculators are not allowed.");
    await page.getByRole("button", { name: "Add questions" }).click();
    const picker = page.getByRole("dialog");
    await picker.getByLabel("Bank").selectOption({ label: `E2E Assessment bank ${RUN}` });
    await picker.getByLabel("Search").fill(`Assessment ${RUN}`);
    await picker.getByLabel(new RegExp(`question 1:`)).check();
    await picker.getByLabel(new RegExp(`question 2:`)).check();
    await expect(picker.getByText("Secret explanation")).toHaveCount(0);            // the picker never shows answers
    await picker.getByRole("button", { name: "Add 2" }).click();
    await page.getByRole("button", { name: "Add rule" }).click();
    await page.getByLabel("Bank").last().selectOption({ label: `E2E Assessment bank ${RUN}` });
    await page.getByLabel("Draw").fill("1");
    await page.getByLabel("Marks", { exact: true }).last().fill("2");
    await expect(page.getByText(/^\d+ approved questions match/)).toBeVisible();
    await expect(page.getByText("3 questions · 6 marks")).toBeVisible();
    await axe(page);
    await page.getByRole("button", { name: "Save draft" }).click();

    await expect(page.getByText("Ready to submit")).toBeVisible();
    await page.getByRole("button", { name: "Submit for review" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Submit" }).click();
    await expect(page.getByText("In review").first()).toBeVisible();
    // the author can neither review nor publish
    await expect(page.getByRole("button", { name: "Mark as reviewed" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Approve and publish" })).toHaveCount(0);
    await axe(page);
    expectNoAnswers(bodies);
  });

  test("2 · a reviewer reviews it; a third person publishes it", async ({ page }) => {
    const bodies = recordApi(page);
    await signIn(page, REVIEWER, "/assessments/queue");
    await page.getByRole("link", { name: TITLE }).click();
    await page.getByRole("button", { name: "Mark as reviewed" }).click();
    await page.getByRole("dialog").getByLabel("Comment (optional)").fill("Balanced paper.");
    await page.getByRole("dialog").getByRole("button", { name: "Mark as reviewed" }).click();
    await page.getByRole("tab", { name: /Reviews/ }).click();
    await expect(page.getByText("Balanced paper.")).toBeVisible();

    await page.context().clearCookies();
    await signIn(page, PUBLISHER, "/assessments/queue");
    await page.getByRole("tab", { name: "To publish" }).click();
    await page.getByRole("link", { name: TITLE }).click();
    await page.getByRole("button", { name: "Approve and publish" }).click();
    await page.getByRole("dialog").getByLabel("Reason (recorded)").fill("Approved for the semester examination.");
    await page.getByRole("dialog").getByRole("button", { name: "Publish" }).click();
    await expect(page.getByText("Published").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit draft" })).toHaveCount(0);   // published versions never change
    await page.getByRole("tab", { name: "History" }).click();
    await expect(page.getByRole("cell", { name: "In review → Published" })).toBeVisible();
    expectNoAnswers(bodies);
  });

  test("3 · a paper is previewed without answers and reproduced from its seed", async ({ page, request }) => {
    const id = await assessmentId(request);
    const bodies = recordApi(page);
    await signIn(page, AUTHOR, `/assessments/${id}`);
    await page.getByRole("button", { name: "Preview a paper" }).click();
    await expect(page.getByRole("heading", { name: new RegExp(`Paper preview — ${TITLE}`) })).toBeVisible();
    await expect(page.getByRole("heading", { name: /^Question \d$/ })).toHaveCount(3);
    await expect(page.getByText(`Secret explanation ${RUN}`)).toHaveCount(0);
    await page.getByRole("button", { name: "Reproduce from seed" }).click();
    await expect(page.getByText("The stored seed reproduces this paper exactly.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Disclose seed/ })).toHaveCount(0);   // only holders of assessment.publish
    await axe(page);
    expectNoAnswers(bodies);
    // excerpts are the statement only: option text is not part of the question line
    await page.goto(`/assessments/${id}`);
    await expect(page.getByRole("cell", { name: new RegExp(`question 1: which option is right\\?$`) })).toBeVisible();
  });
});
