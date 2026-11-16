import { expect, test, type APIRequestContext } from "@playwright/test";
import { AUTHOR, COLLEGE, FACULTY, PUBLISHER, REVIEWER, SECRET_EXPLANATION, checkInAndStart, expectNoAnswerInformation, goToQuestion, recordDelivery, registerCandidate, schedule }
  from "./exam-fixture";
import { staffApi, staffToken } from "./support";

/**
 * Add-on C (Session 4): a paper with sections is delivered by the existing examination engine. The candidate sees each
 * section's title and instructions, the question navigator is grouped by section, sections keep their order although
 * questions are shuffled within them, and no answer information reaches the browser. Fixture through the staff API only.
 */
const RUN = Date.now().toString(36);
const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });
let version = "";

/** Three approved single-choice questions; sections "Part A" (questions 1–2, shuffled) and "Part B" (question 3). */
async function publishedSectionedVersion(request: APIRequestContext): Promise<string> {
  const author = await staffToken(request, AUTHOR);
  const faculty = await staffToken(request, FACULTY);
  const reviewer = await staffToken(request, REVIEWER);
  const publisher = await staffToken(request, PUBLISHER);
  const bank = await staffApi<{ id: string }>(request, author, "POST", "/api/v1/question-banks", {
    orgUnitId: COLLEGE, code: `SE${RUN}`.slice(0, 40), name: `E2E sections bank ${RUN}`, intendedUses: ["ACADEMIC"],
    policy: { approvalPolicy: "STANDARD", requiredReviews: 1, approverDistinct: false } });
  const ids: string[] = [];
  for (let i = 1; i <= 3; i++) {
    const q = await staffApi<{ id: string }>(request, faculty, "POST", `/api/v1/question-banks/${bank.id}/questions`, {
      type: "SINGLE_CHOICE", content: content(`Sections ${RUN} question ${i}`), marks: 2,
      body: { options: [{ id: "A", content: content(`Alpha ${i}`) }, { id: "B", content: content(`Beta ${i}`) }] },
      answerKey: { correct: "B" }, explanation: SECRET_EXPLANATION });
    await staffApi(request, faculty, "POST", `/api/v1/questions/${q.id}/submit`, {});
    await staffApi(request, reviewer, "POST", `/api/v1/questions/${q.id}/reviews`, { decision: "REVIEWED", checklist: { accurate: true } });
    await staffApi(request, publisher, "POST", `/api/v1/questions/${q.id}/approve`, { reason: "E2E" });
    const detail = await staffApi<{ current: { id: string; state: string }; approved?: { id: string } | null }>(request, author, "GET", `/api/v1/questions/${q.id}`);
    ids.push(detail.approved?.id ?? detail.current.id);
  }
  const a = await staffApi<{ id: string }>(request, author, "POST", "/api/v1/assessments", {
    orgUnitId: COLLEGE, code: `SE-${RUN}`.slice(0, 40), purpose: "INTERNAL", title: `Sectioned examination ${RUN}`, durationMinutes: 30 });
  const draft = await staffApi<{ current: { version: number } }>(request, author, "GET", `/api/v1/assessments/${a.id}`);
  await staffApi(request, author, "PUT", `/api/v1/assessments/${a.id}/draft`, {
    title: `Sectioned examination ${RUN}`, durationMinutes: 30, shuffleQuestions: true,
    sections: [{ sectionNo: 1, title: "Part A — Reading", instructions: "Read each passage before answering." }, { sectionNo: 2, title: "Part B — Problems" }],
    items: [{ questionVersionId: ids[0], sectionNo: 1 }, { questionVersionId: ids[1], sectionNo: 1 }, { questionVersionId: ids[2], sectionNo: 2 }],
    version: draft.current.version });
  await staffApi(request, author, "POST", `/api/v1/assessments/${a.id}/submit`, {});
  await staffApi(request, reviewer, "POST", `/api/v1/assessments/${a.id}/reviews`, { decision: "REVIEWED" });
  await staffApi(request, publisher, "POST", `/api/v1/assessments/${a.id}/publish`, {});
  const after = await staffApi<{ current: { id: string; state: string }; published?: { id: string } | null }>(request, author, "GET", `/api/v1/assessments/${a.id}`);
  return after.published?.id ?? after.current.id;
}

test.beforeAll(async ({ request }) => {
  test.setTimeout(180_000);
  version = await publishedSectionedVersion(request);
});

test("a sectioned paper shows each section's title and instructions, in order, without answer information", async ({ page, request }) => {
  test.setTimeout(240_000);
  const email = `sections.${RUN}@candidates.test`;
  const exam = await schedule(request, version, `Sectioned examination ${RUN}`, [email]);
  const bodies = recordDelivery(page);
  await registerCandidate(page, exam.key, email, `Exam-${Date.now()}-pw!`);
  await checkInAndStart(page, exam, exam.codes[0]!);

  const nav = page.getByRole("navigation", { name: "Questions" });
  await expect(nav.getByRole("group", { name: "Section 1: Part A — Reading" }).getByRole("button")).toHaveCount(2);
  await expect(nav.getByRole("group", { name: "Section 2: Part B — Problems" }).getByRole("button")).toHaveCount(1);

  // question 1 belongs to Part A, whatever the shuffle within the section
  await expect(page.getByRole("heading", { name: "Section 1: Part A — Reading" })).toBeVisible();
  await expect(page.getByText("Read each passage before answering.")).toBeVisible();
  await expect(page.getByText(new RegExp(`Sections ${RUN} question [12]$`))).toBeVisible();
  await goToQuestion(page, 3);
  await expect(page.getByRole("heading", { name: "Section 2: Part B — Problems" })).toBeVisible();
  await expect(page.getByText(`Sections ${RUN} question 3`)).toBeVisible();
  await expect(page.getByText("Read each passage before answering.")).toHaveCount(0);

  // answering still goes through the unchanged journal
  await page.getByLabel("Beta 3").check();
  await expect(page.getByText("All answers saved")).toBeVisible();
  expectNoAnswerInformation(bodies);
});
