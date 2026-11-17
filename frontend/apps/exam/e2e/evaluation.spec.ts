import { expect, test } from "@playwright/test";
import { checkInAndStart, content, FACULTY, goToQuestion, PUBLISHER, publishedVersion, registerCandidate, saveStatus, schedule } from "./exam-fixture";
import { password, STAFF, staffApi, staffToken } from "./support";

/**
 * M-G (contract C-2) in real browsers: a candidate writes a descriptive answer; the exam controller sets up single
 * marking with an evaluator and allocates the anonymous script on the schedule page; the evaluator marks it against the
 * rubric on their own screen, without ever seeing who wrote it; the attempt's score is then complete and provisional.
 */
const RUN = Date.now().toString(36);
let version = "";

test.beforeAll(async ({ request }) => {
  test.setTimeout(180_000);
  version = await publishedVersion(request, `${RUN}e`, 30, 2, [{
    type: "DESCRIPTIVE", content: content(`Explain recursion ${RUN}.`), marks: 10, negativeMarks: 0, body: { maxWords: 50 },
    answerKey: { modelAnswer: "A function that calls itself with a smaller input until a base case." },
    marking: { rubric: [{ title: "Idea", weight: 70 }, { title: "Base case", weight: 30 }] } }]);
});

test("an evaluator marks an anonymous script against the rubric and the score becomes complete", async ({ page, browser, request }) => {
  test.setTimeout(360_000);
  const email = `evaluation.${RUN}@candidates.test`;
  const exam = await schedule(request, version, `Examination ${RUN}e`, [email]);
  await registerCandidate(page, exam.key, email, `Exam-${Date.now()}-pw!`);
  await checkInAndStart(page, exam, exam.codes[0]!);
  let n = 1;
  for (; n <= 3; n++) { await goToQuestion(page, n); if (await page.locator("textarea#descriptive-answer").count()) break; }
  const answer = `Recursion ${RUN}: a function calls itself on a smaller problem and stops at a base case.`;
  await page.getByRole("textbox", { name: "Your answer" }).fill(answer);
  await expect(saveStatus(page)).toHaveText("All answers saved");
  await goToQuestion(page, 3);
  await page.getByRole("button", { name: "Finish examination" }).click();
  await page.getByRole("button", { name: "Submit now" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Your examination has been submitted." })).toBeVisible();

  const ctl = await staffToken(request, PUBLISHER);
  const results = () => staffApi<{ awaitingMarks: number; provisional: number; rows: { awarded: number | null }[] }>(request, ctl, "GET", `/api/v1/exam/results/${exam.scheduleId}`);
  await expect.poll(async () => (await results()).awaitingMarks, { timeout: 90_000, intervals: [2_000] }).toBe(1);

  // the exam controller: single marking by Arun, then allocation
  const staffPage = async (user: string, path: string) => {
    const p = await (await browser.newContext()).newPage();
    await p.goto(`${STAFF}${path}`);
    await p.locator("#username").fill(user);
    await p.locator("#password").fill(password());
    await p.locator("#kc-login").click();
    return p;
  };
  const c = await staffPage(PUBLISHER, `/exams/schedules/${exam.scheduleId}`);
  await expect(c.getByRole("heading", { name: "Evaluation" })).toBeVisible();
  await c.getByLabel("Marking").selectOption("SINGLE");
  await c.getByRole("checkbox", { name: "Arun Faculty" }).check();
  await c.getByRole("button", { name: "Save evaluation setup" }).click();
  await expect(c.getByRole("button", { name: "Allocate scripts" })).toBeEnabled();
  await c.getByRole("button", { name: "Allocate scripts" }).click();
  await expect(c.getByTestId("evaluation-progress")).toContainText("1 script(s)");
  await expect(c.getByTestId("evaluation-progress")).toContainText("Arun Faculty: 1 to mark");

  // the evaluator: their own task, anonymous
  const ev = await staffPage(FACULTY, "/evaluation");
  await ev.getByRole("link", { name: /^S-.* · question \d$/ }).first().click();
  await expect(ev.getByTestId("evaluation-response")).toHaveText(answer);
  await expect(ev.getByText("A function that calls itself with a smaller input")).toBeVisible();       // model answer, audited
  await expect(ev.locator("body")).not.toContainText(email);
  await ev.getByLabel(/^Idea \(0–7/).fill("6");
  await ev.getByLabel(/^Base case \(0–3/).fill("2.5");
  await expect(ev.getByTestId("evaluation-total")).toHaveText("Total: 8.5 / 10");
  await ev.getByRole("button", { name: "Submit marks" }).click();
  await expect(ev.getByText(/Submitted — final\./)).toBeVisible();
  await expect(ev.getByRole("button", { name: "Submit marks" })).toHaveCount(0);

  // the score is complete: objective question unanswered (0) + 8.5
  const r = await results();
  expect(r.awaitingMarks).toBe(0);
  expect(r.provisional).toBe(1);
  expect(Number(r.rows[0]!.awarded)).toBe(8.5);
  await c.reload();
  await expect(c.getByTestId("evaluation-progress")).toContainText("1 final");
});
