import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { mail, staffApi, staffToken } from "./support";

/**
 * Fixture for the candidate examination journeys (QA, D-7), built only through the real staff API: approved questions,
 * an assessment authored, reviewed and published by different people, an exam portal, a schedule, an enrolment upload
 * and session codes. Synthetic data only.
 */
export const AUTHOR = "alice.admin@aurora.test";          // holds assessment.author and question-bank management
export const REVIEWER = "ines.instadmin@aurora.test";     // institution administrator: assessment.review
export const PUBLISHER = "eshan.controller@aurora.test";  // exam controller: assessment.publish, scheduling, codes
export const FACULTY = "arun.faculty@aurora.test";        // authors questions
export const COLLEGE = "0192a000-0000-7000-8000-00000000a101";

const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });

export const SECRET_EXPLANATION = "Secret explanation for the examination journeys";

/** Publishes an assessment of {@code questions} single-choice questions (correct answer B) and returns its published version id. */
export async function publishedVersion(request: APIRequestContext, run: string, durationMinutes: number, questions = 3): Promise<string> {
  const author = await staffToken(request, AUTHOR);
  const faculty = await staffToken(request, FACULTY);
  const reviewer = await staffToken(request, REVIEWER);
  const publisher = await staffToken(request, PUBLISHER);
  const bank = await staffApi<{ id: string }>(request, author, "POST", "/api/v1/question-banks", {
    orgUnitId: COLLEGE, code: `EX${run}`.slice(0, 40), name: `E2E examination bank ${run}`, intendedUses: ["ACADEMIC"],
    policy: { approvalPolicy: "STANDARD", requiredReviews: 1, approverDistinct: false } });
  const ids: string[] = [];
  for (let i = 1; i <= questions; i++) {
    const q = await staffApi<{ id: string }>(request, faculty, "POST", `/api/v1/question-banks/${bank.id}/questions`, {
      type: "SINGLE_CHOICE", content: content(`Examination ${run} question ${i}`), marks: 2, negativeMarks: 0.5,
      body: { options: [{ id: "A", content: content(`Alpha ${i}`) }, { id: "B", content: content(`Beta ${i}`) }, { id: "C", content: content(`Gamma ${i}`) }] },
      answerKey: { correct: "B" }, explanation: SECRET_EXPLANATION });
    await staffApi(request, faculty, "POST", `/api/v1/questions/${q.id}/submit`, {});
    await staffApi(request, reviewer, "POST", `/api/v1/questions/${q.id}/reviews`, { decision: "REVIEWED", checklist: { accurate: true } });
    await staffApi(request, publisher, "POST", `/api/v1/questions/${q.id}/approve`, { reason: "E2E" });
    // the approved version is reported separately only when it differs from the current one
    const detail = await staffApi<{ current: { id: string; state: string }; approved?: { id: string } | null }>(request, author, "GET", `/api/v1/questions/${q.id}`);
    const approved = detail.approved?.id ?? (detail.current.state === "APPROVED" ? detail.current.id : undefined);
    expect(approved, "question approved").toBeTruthy();
    ids.push(approved!);
  }
  const a = await staffApi<{ id: string }>(request, author, "POST", "/api/v1/assessments", {
    orgUnitId: COLLEGE, code: `EX-${run}`.slice(0, 40), purpose: "INTERNAL", title: `Examination ${run}`, durationMinutes });
  const draft = await staffApi<{ current: { version: number } }>(request, author, "GET", `/api/v1/assessments/${a.id}`);
  await staffApi(request, author, "PUT", `/api/v1/assessments/${a.id}/draft`, {
    title: `Examination ${run}`, durationMinutes, items: ids.map((questionVersionId) => ({ questionVersionId })), version: draft.current.version });
  await staffApi(request, author, "POST", `/api/v1/assessments/${a.id}/submit`, {});
  await staffApi(request, reviewer, "POST", `/api/v1/assessments/${a.id}/reviews`, { decision: "REVIEWED" });
  await staffApi(request, publisher, "POST", `/api/v1/assessments/${a.id}/publish`, {});
  const after = await staffApi<{ current: { id: string; state: string }; published?: { id: string } | null }>(request, author, "GET", `/api/v1/assessments/${a.id}`);
  const published = after.published?.id ?? (after.current.state === "PUBLISHED" ? after.current.id : undefined);
  expect(published, `assessment published: ${JSON.stringify(after).slice(0, 300)}`).toBeTruthy();
  return published!;
}

export interface Exam { key: string; scheduleId: string; codes: string[]; title: string }

/** A fresh portal and a schedule of the published version, the given candidates enrolled by CSV, one code per candidate. */
export async function schedule(request: APIRequestContext, versionId: string, title: string, emails: string[], closesInMinutes = 120): Promise<Exam> {
  const token = await staffToken(request, PUBLISHER);
  const key = `ex-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  const portal = await staffApi<{ id: string }>(request, token, "POST", "/api/v1/exam/portals", { portalKey: key, name: `E2E examinations ${key}`, mfaPolicy: "OFF", selfRegistration: true });
  const now = Date.now();
  const s = await staffApi<{ id: string }>(request, token, "POST", "/api/v1/exam/schedules", {
    versionId, portalId: portal.id, orgUnitId: COLLEGE, opensAt: new Date(now - 60_000).toISOString(), closesAt: new Date(now + closesInMinutes * 60_000).toISOString() });
  const upload = await staffApi<{ created: number }>(request, token, "POST", `/api/v1/exam/schedules/${s.id}/enrolments/upload`, {
    csv: `email,name\n${emails.map((e) => `${e},Candidate ${e.slice(0, 6)}`).join("\n")}\n`, apply: true });
  expect(upload.created).toBe(emails.length);
  const codes = await staffApi<{ code: string }[]>(request, token, "POST", `/api/v1/exam/schedules/${s.id}/codes`, { count: emails.length });
  return { key, scheduleId: s.id, codes: codes.map((c) => c.code), title };
}

/** Registers a new candidate through the real candidate realm, including email verification; ends signed in on the portal. */
export async function registerCandidate(page: Page, key: string, email: string, secret: string) {
  await page.goto(`/p/${key}`);
  await page.getByRole("button", { name: "Create an exam account" }).click();
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill("Exam");
  await page.locator("#lastName").fill("Candidate");
  const pw = page.locator("#password");
  if (await pw.count()) {
    await pw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
  }
  await page.locator("input[type=submit], button[type=submit]").first().click();
  await expect(page.getByText(/verify your email|email.*sent|check your email/i).first()).toBeVisible();
  const text = await mail(page.request, email, "erif");
  const link = /(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(text)![1]!;
  await page.goto(link.replace(/&amp;/g, "&"));
  const proceed = page.getByRole("link", { name: /click here to proceed|back to application/i });
  if (await proceed.isVisible().catch(() => false)) await proceed.click();
  const newPw = page.locator("#password-new");
  if (await newPw.isVisible({ timeout: 5000 }).catch(() => false)) {
    await newPw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
    await page.locator("input[type=submit], button[type=submit]").first().click();
  }
  await expect(page.getByText(email)).toBeVisible();
}

/** Every delivery response body the browser receives (to prove that no answer information reaches the candidate). */
export function recordDelivery(page: Page): string[] {
  const bodies: string[] = [];
  page.on("response", (r) => {
    if (!r.url().includes("/api/v1/delivery/")) return;
    void r.text().then((t) => bodies.push(t)).catch(() => undefined);
  });
  return bodies;
}

export function expectNoAnswerInformation(bodies: string[]) {
  expect(bodies.length).toBeGreaterThan(0);
  const all = bodies.join("\n");
  expect(all).not.toContain(SECRET_EXPLANATION);
  expect(all).not.toContain("answerKey");
  expect(all).not.toContain('"correct"');
  expect(all).not.toContain('"explanation"');
  expect(all).not.toContain("awarded");
}
