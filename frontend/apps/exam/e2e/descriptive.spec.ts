import { expect, test } from "@playwright/test";
import { checkInAndStart, content, expectNoAnswerInformation, goToQuestion, PUBLISHER, publishedVersion, recordDelivery, registerCandidate, saveStatus, schedule } from "./exam-fixture";
import { staffApi, staffToken } from "./support";

/**
 * M-D (contract C-1): a descriptive question in a real browser. The candidate sees the word limit and a live count, cannot
 * exceed the limit, the written answer autosaves and survives a reload; after submission the attempt waits for evaluators
 * instead of being scored, and neither the model answer nor the rubric ever reaches the browser.
 */
const RUN = Date.now().toString(36);
const MODEL = `Model answer ${RUN}: a stack is LIFO and a queue is FIFO.`;
let version = "";

test.beforeAll(async ({ request }) => {
  test.setTimeout(180_000);
  version = await publishedVersion(request, `${RUN}d`, 30, 2, [{
    type: "DESCRIPTIVE", content: content(`Compare a stack and a queue ${RUN}.`), marks: 10, negativeMarks: 0,
    body: { maxWords: 12 }, answerKey: { modelAnswer: MODEL },
    marking: { rubric: [{ title: `Correctness ${RUN}`, description: "Both orders named", weight: 70 }, { title: "Clarity", weight: 30 }] } }]);
});

test("a written answer within the word limit is saved, survives a reload and waits for evaluation after submission", async ({ page, request }) => {
  test.setTimeout(300_000);
  const email = `descriptive.${RUN}@candidates.test`;
  const exam = await schedule(request, version, `Examination ${RUN}d`, [email]);
  const bodies = recordDelivery(page);
  await registerCandidate(page, exam.key, email, `Exam-${Date.now()}-pw!`);
  await checkInAndStart(page, exam, exam.codes[0]!);

  // find the written-answer question (paper order is per candidate)
  let n = 1;
  for (; n <= 3; n++) {
    await goToQuestion(page, n);
    if (await page.getByLabel("Your answer").locator("xpath=self::textarea").count()) break;
  }
  expect(n).toBeLessThanOrEqual(3);
  const box = page.getByRole("textbox", { name: "Your answer" });
  await expect(page.getByText("0 / 12 words")).toBeVisible();
  await box.fill("A stack is last in first out; a queue is first in first out.");
  await expect(page.getByText("14 / 12 words")).toHaveCount(0);                     // over the limit: refused
  await expect(page.getByText(/word limit is reached/)).toBeVisible();
  await box.fill("Stack: last in, first out. Queue: first in, first out.");
  await expect(page.getByText("10 / 12 words")).toBeVisible();
  await expect(saveStatus(page)).toHaveText("All answers saved");

  await page.reload();
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(email)).toBeVisible();
  if (!page.url().includes("/attempts/")) await page.getByRole("region", { name: "Your examinations" }).getByRole("button", { name: "Resume examination" }).click();
  await goToQuestion(page, n);
  await expect(page.getByRole("textbox", { name: "Your answer" })).toHaveValue("Stack: last in, first out. Queue: first in, first out.");

  await goToQuestion(page, 3);
  await page.getByRole("button", { name: "Finish examination" }).click();
  await page.getByRole("button", { name: "Submit now" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Your examination has been submitted." })).toBeVisible();

  // staff: scored automatically except the written answer, which waits for evaluators
  const token = await staffToken(request, PUBLISHER);
  await expect.poll(async () => (await staffApi<{ awaitingMarks: number }>(request, token, "GET", `/api/v1/exam/results/${exam.scheduleId}`)).awaitingMarks,
    { timeout: 90_000, intervals: [2_000] }).toBe(1);

  expectNoAnswerInformation(bodies);
  const all = bodies.join("\n");
  for (const secret of [MODEL, "modelAnswer", `Correctness ${RUN}`, "rubric", "Both orders named"]) expect(all).not.toContain(secret);
});
