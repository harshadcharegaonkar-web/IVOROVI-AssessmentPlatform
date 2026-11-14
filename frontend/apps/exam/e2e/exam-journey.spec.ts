import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { DELIVERY, STAFF, candidateToken, mail, staffApi, staffToken } from "./support";

/**
 * Day 3 acceptance journey (decision D-10), on the real stack (Keycloak, staff and delivery APIs, PostgreSQL, Mailpit):
 * published assessment → scheduled examination → invitation → candidate sign-up and login → check-in → start →
 * autosave with server acknowledgement → page reload recovery → network interruption and reconciliation → submission →
 * duplicate submission → scoring → review → refused self-approval → independent approval → publication → candidate result.
 * A second test lets time run out and checks the server submits the attempt on its own. Synthetic data only.
 */
const AUTHOR = "alice.admin@aurora.test";
const FACULTY = "arun.faculty@aurora.test";
const HEAD = "ines.instadmin@aurora.test";          // institution admin: reviews questions, assessments and results
const CONTROLLER = "eshan.controller@aurora.test";  // approves questions, publishes, schedules, approves result publication (privileged)
const COLLEGE = "0192a000-0000-7000-8000-00000000a101";
const RUN = Date.now().toString(36);

const content = (text: string) => ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }] });
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=", "base64");
const withImage = (text: string, asset: string, alt: string) =>
  ({ format: "ivorovi.content.v1", blocks: [{ type: "paragraph", runs: [{ text }] }, { type: "image", asset, alt }] });

async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

/**
 * Three approved questions (correct answer: "Option beta") and a published INTERNAL assessment of the given duration.
 * With {@code images}, question 1 has an image in its statement and in option B (KI-83).
 */
async function publishedVersion(request: APIRequestContext, label: string, minutes: number, images = false): Promise<string> {
  const t = { author: await staffToken(request, AUTHOR), faculty: await staffToken(request, FACULTY), head: await staffToken(request, HEAD), ctl: await staffToken(request, CONTROLLER) };
  const bank = await staffApi<{ id: string }>(request, t.author, "POST", "/api/v1/question-banks", { orgUnitId: COLLEGE, code: `J${label}${RUN}`.slice(0, 40),
    name: `E2E journey ${label} ${RUN}`, intendedUses: ["ACADEMIC"], policy: { approvalPolicy: "STANDARD", requiredReviews: 1, approverDistinct: false } });
  let asset: string | null = null;
  if (images) {
    const up = await request.post(`${STAFF}/api/v1/question-banks/${bank.id}/assets`, { headers: { Authorization: `Bearer ${t.faculty}` },
      multipart: { file: { name: "triangle.png", mimeType: "image/png", buffer: PNG }, altText: "A labelled triangle" } });
    expect(up.status()).toBe(201);
    asset = ((await up.json()) as { id: string }).id;
  }
  const versions: string[] = [];
  for (let i = 1; i <= 3; i++) {
    const stem = `Journey ${label} ${RUN} question ${i}: which option is right?`;
    const q = await staffApi<{ id: string }>(request, t.faculty, "POST", `/api/v1/question-banks/${bank.id}/questions`, {
      type: "SINGLE_CHOICE", content: asset && i === 1 ? withImage(stem, asset, "A labelled triangle") : content(stem), marks: 2, negativeMarks: 0.5,
      body: { options: [{ id: "A", content: content("Option alpha") }, { id: "B", content: asset && i === 1 ? withImage("Option beta", asset, "Right angle") : content("Option beta") },
        { id: "C", content: content("Option gamma") }] },
      answerKey: { correct: "B" }, explanation: `Explanation ${RUN}` });
    await staffApi(request, t.faculty, "POST", `/api/v1/questions/${q.id}/submit`, {});
    await staffApi(request, t.head, "POST", `/api/v1/questions/${q.id}/reviews`, { decision: "REVIEWED", checklist: { accurate: true } });
    const approved = await staffApi<{ approved?: { id: string }; current?: { id: string } }>(request, t.ctl, "POST", `/api/v1/questions/${q.id}/approve`, { reason: "E2E" });
    versions.push((approved.approved ?? approved.current)!.id);
  }
  const a = await staffApi<{ id: string; current: { version: number } }>(request, t.author, "POST", "/api/v1/assessments",
    { orgUnitId: COLLEGE, code: `JA-${label}-${RUN}`, purpose: "INTERNAL", title: `Journey ${label} ${RUN}`, durationMinutes: minutes });
  const d = await staffApi<{ current: { version: number } }>(request, t.author, "PUT", `/api/v1/assessments/${a.id}/draft`, {
    title: `Journey ${label} ${RUN}`, durationMinutes: minutes, shuffleOptions: true, items: versions.map((v) => ({ questionVersionId: v })), version: a.current.version });
  expect(d.current).toBeTruthy();
  await staffApi(request, t.author, "POST", `/api/v1/assessments/${a.id}/submit`, {});
  await staffApi(request, t.head, "POST", `/api/v1/assessments/${a.id}/reviews`, { decision: "REVIEWED" });
  const p = await staffApi<{ current: { id: string } }>(request, t.ctl, "POST", `/api/v1/assessments/${a.id}/publish`, { reason: "E2E journey" });
  return p.current.id;
}

/** Portal, schedule, enrolment of one email, invitation, one session code. */
async function scheduled(request: APIRequestContext, versionId: string, email: string, closesInMinutes: number) {
  const ctl = await staffToken(request, CONTROLLER);
  const key = `j-${RUN}-${Math.random().toString(36).slice(2, 6)}`;
  const portal = await staffApi<{ id: string }>(request, ctl, "POST", "/api/v1/exam/portals", { portalKey: key, name: `E2E entrance ${key}`, mfaPolicy: "OFF", selfRegistration: true });
  const now = Date.now();
  const schedule = await staffApi<{ id: string; title: string }>(request, ctl, "POST", "/api/v1/exam/schedules", { versionId, portalId: portal.id, orgUnitId: COLLEGE,
    opensAt: new Date(now - 60_000).toISOString(), closesAt: new Date(now + closesInMinutes * 60_000).toISOString(), instructions: "Bring your identity card." });
  const up = await staffApi<{ created: number }>(request, ctl, "POST", `/api/v1/exam/schedules/${schedule.id}/enrolments/upload`, { csv: `${email},Journey Candidate\n`, apply: true });
  expect(up.created).toBe(1);
  const inv = await staffApi<{ sent: number }>(request, ctl, "POST", `/api/v1/exam/schedules/${schedule.id}/invitations`);
  expect(inv.sent).toBe(1);
  const codes = await staffApi<{ code: string }[]>(request, ctl, "POST", `/api/v1/exam/schedules/${schedule.id}/codes`, { count: 1 });
  return { key, scheduleId: schedule.id, title: schedule.title, code: codes[0]!.code, ctl };
}

/** Opens the invitation link and registers with the invited address (real candidate realm, email verification). */
async function acceptInvitation(page: Page, request: APIRequestContext, email: string, secret: string, title: string) {
  const text = await mail(request, email, "Examination");
  expect(text).toContain(title);
  const path = /(\/p\/[a-z0-9-]+)/.exec(text)![1]!;
  await page.goto(path);
  await page.getByRole("button", { name: "Create an exam account" }).click();
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill("Journey");
  await page.locator("#lastName").fill("Candidate");
  const pw = page.locator("#password");
  if (await pw.count()) {
    await pw.fill(secret);
    await page.locator("#password-confirm").fill(secret);
  }
  await page.locator("input[type=submit], button[type=submit]").first().click();
  const verify = await mail(request, email, "erif");
  await page.goto(/(http:\/\/localhost:8180\/realms\/ivorovi-candidates\/login-actions\/action-token\S+)/.exec(verify)![1]!.replace(/&amp;/g, "&"));
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

async function checkInAndStart(page: Page, code: string, title: string) {
  await expect(page.getByText("Check in with your session code first")).toBeVisible();
  await page.getByLabel("Session code").fill(code);
  await page.getByRole("button", { name: "Check in" }).click();
  await expect(page.getByText(`Checked in to ${title}.`)).toBeVisible();
  await page.getByRole("button", { name: "Start examination" }).click();
  await expect(page.getByRole("heading", { name: /Question 1 of 3/ })).toBeVisible();
}

const saved = (page: Page) => expect(page.getByText("All answers saved")).toBeVisible({ timeout: 15_000 });

test.describe("Day 3: candidate examination journey with independent result publication", () => {
  test("invitation → login → check-in → answers (reload, offline) → submit → score → review → approval → result", async ({ page, request, context }) => {
    test.setTimeout(420_000);
    const version = await publishedVersion(request, "A", 30);
    const email = `journey.${RUN}@candidates.test`;
    const secret = `Cand-${RUN}-pw!`;
    const s = await scheduled(request, version, email, 120);

    // invitation → registration with the invited email → the examination is listed
    await acceptInvitation(page, request, email, secret, s.title);
    await expect(page.getByRole("heading", { name: "Your examinations" })).toBeVisible();
    await expect(page.getByText(s.title).first()).toBeVisible();
    await axe(page);
    await checkInAndStart(page, s.code, s.title);
    // the timer is the server's: at most the 30 minutes of the assessment remain
    const timer = await page.getByRole("timer").innerText();
    expect(Number(timer.split(":")[0])).toBeLessThanOrEqual(30);
    await axe(page);

    // autosave: an answer is acknowledged only once the server stored it
    await page.getByRole("radio", { name: /Option beta/ }).check();
    await saved(page);
    await page.getByRole("button", { name: "Next" }).click();
    await page.getByRole("radio", { name: /Option alpha/ }).check();          // a wrong answer (−0.5)
    await saved(page);

    // reload: tokens live in memory, so the candidate signs in again (single sign-on) and resumes with the saved answers
    await page.reload();
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("button", { name: "Resume examination" }).click();
    await expect(page.getByRole("radio", { name: /Option beta/ })).toBeChecked();
    await page.getByRole("button", { name: "Question 2, answered" }).click();
    await expect(page.getByRole("radio", { name: /Option alpha/ })).toBeChecked();

    // network interruption: the answer is kept in the browser and reconciled when the connection returns
    await page.getByRole("button", { name: /^Question 3/ }).click();
    await context.setOffline(true);
    await page.getByRole("radio", { name: /Option beta/ }).check();
    await expect(page.getByText("Saving your answers…")).toBeVisible();
    await page.waitForTimeout(2_000);
    await context.setOffline(false);
    await saved(page);

    // candidate submission, then a duplicate submission returns the same result
    await page.getByRole("button", { name: "Finish examination" }).click();
    await expect(page.getByText("You answered 3 of 3 questions.")).toBeVisible();
    await page.getByRole("button", { name: "Submit now" }).click();
    await expect(page.getByText("Your examination has been submitted.")).toBeVisible();
    await expect(page.getByText("Your result will appear here once your institution has published it.")).toBeVisible();
    const cand = await candidateToken(request, email, secret);
    const exams = await (await request.get(`${DELIVERY}/api/v1/delivery/portals/${s.key}/exams`, { headers: { Authorization: `Bearer ${cand}` } })).json() as { attemptId: string }[];
    const attemptId = exams[0]!.attemptId;
    const again = await request.post(`${DELIVERY}/api/v1/delivery/portals/${s.key}/attempts/${attemptId}/submit`, { headers: { Authorization: `Bearer ${cand}` }, data: { submitKey: "dup" } });
    const twice = await request.post(`${DELIVERY}/api/v1/delivery/portals/${s.key}/attempts/${attemptId}/submit`, { headers: { Authorization: `Bearer ${cand}` }, data: { submitKey: "dup" } });
    expect(again.status()).toBe(200);
    expect(await twice.json()).toEqual(await again.json());
    const late = await request.put(`${DELIVERY}/api/v1/delivery/portals/${s.key}/attempts/${attemptId}/answers/1`, { headers: { Authorization: `Bearer ${cand}` }, data: { seq: Date.now() + 1e6, response: { value: "A" } } });
    expect(late.status()).toBe(409);                                           // no change after submission

    // scoring (automatic, server side): 2 + (−0.5) + 2 = 3.5 of 6
    let rows: { awarded?: number; maxMarks?: number; scoreStatus?: string }[] = [];
    await expect(async () => {
      const r = await staffApi<{ rows: typeof rows }>(request, s.ctl, "GET", `/api/v1/exam/results/${s.scheduleId}`);
      rows = r.rows;
      expect(rows[0]?.scoreStatus).toBe("PROVISIONAL");
    }).toPass({ timeout: 120_000 });
    expect(rows[0]!.awarded).toBe(3.5);
    expect(rows[0]!.maxMarks).toBe(6);
    const hidden = await request.get(`${DELIVERY}/api/v1/delivery/portals/${s.key}/attempts/${attemptId}/result`, { headers: { Authorization: `Bearer ${cand}` } });
    expect(hidden.status()).toBe(409);                                         // not published yet

    // review by the head of institution; the reviewer cannot approve; the exam controller approves independently
    const head = await staffToken(request, HEAD);
    const reviewed = await staffApi<{ reviews: { approvalId: string; status: string }[] }>(request, head, "POST", `/api/v1/exam/results/${s.scheduleId}/reviews`, { comment: "Checked" });
    const approval = reviewed.reviews[0]!.approvalId;
    const self = await request.post(`${process.env.E2E_STAFF_URL ?? "http://localhost:5173"}/api/v1/approvals/${approval}/decision`,
      { headers: { Authorization: `Bearer ${head}` }, data: { approve: true, reason: "self" } });
    expect(self.status()).toBeGreaterThanOrEqual(400);
    await staffApi(request, s.ctl, "POST", `/api/v1/approvals/${approval}/decision`, { approve: true, reason: "Independent approval" });
    const after = await staffApi<{ released: number }>(request, s.ctl, "GET", `/api/v1/exam/results/${s.scheduleId}`);
    expect(after.released).toBe(1);

    // the candidate sees the published result in the exam app
    await page.reload();
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("button", { name: "View result" }).click();
    await expect(page.getByRole("region", { name: "Your result" })).toContainText("3.5 / 6");
    await expect(page.getByText(`Explanation ${RUN}`)).toHaveCount(0);
    await axe(page);
  });

  test("question images (KI-83): delivered to the candidate's own open attempt only, never cached, closed after submission", async ({ page, request }) => {
    test.setTimeout(420_000);
    const version = await publishedVersion(request, "C", 30, true);
    const email = `images.${RUN}@candidates.test`;
    const secret = `Cand-${RUN}-pw!`;
    const s = await scheduled(request, version, email, 120);                  // schedulable: the image passed the upload checks
    await acceptInvitation(page, request, email, secret, s.title);
    const served = page.waitForResponse((r) => /\/attempts\/[^/]+\/images\//.test(r.url()));
    await checkInAndStart(page, s.code, s.title);
    const res = await served;
    expect(res.status()).toBe(200);
    expect(res.headers()["cache-control"]).toContain("no-store");
    expect(res.headers()["x-content-type-options"]).toBe("nosniff");
    // the statement image and the option image render (as data: URLs: the exam app's CSP allows no other image source)
    const figure = page.getByRole("img", { name: "A labelled triangle" });
    await expect(figure).toBeVisible();
    expect(await figure.getAttribute("src")).toMatch(/^data:image\/png;base64,/);
    await expect(page.getByRole("img", { name: "Right angle" })).toBeVisible();
    await axe(page);

    const cand = await candidateToken(request, email, secret);
    const exams = await (await request.get(`${DELIVERY}/api/v1/delivery/portals/${s.key}/exams`, { headers: { Authorization: `Bearer ${cand}` } })).json() as { attemptId: string }[];
    const attemptId = exams[0]!.attemptId;
    const asset = /\/images\/([0-9a-f-]+)/.exec(res.url())![1]!;
    const img = (id: string, token: string | null) => request.get(`${DELIVERY}/api/v1/delivery/portals/${s.key}/attempts/${attemptId}/images/${id}`,
      token ? { headers: { Authorization: `Bearer ${token}` } } : {});
    expect((await img(asset, cand)).status()).toBe(200);
    expect((await img(asset, null)).status()).toBe(401);                       // no token, no image
    expect((await img("0192a000-0000-7000-8000-000000000000", cand)).status()).toBe(404);   // not in the paper

    await page.getByRole("radio", { name: /Option beta/ }).check();
    await saved(page);
    await page.getByRole("button", { name: "Question 3" }).click();
    await page.getByRole("button", { name: "Finish examination" }).click();
    await page.getByRole("button", { name: "Submit now" }).click();
    await expect(page.getByText("Your examination has been submitted.")).toBeVisible();
    expect((await img(asset, cand)).status()).toBe(404);                       // closed after submission
  });

  test("time runs out on an abandoned attempt: the server submits it automatically", async ({ page, request }) => {
    test.setTimeout(420_000);
    const version = await publishedVersion(request, "B", 1);
    const email = `timeup.${RUN}@candidates.test`;
    const secret = `Cand-${RUN}-pw!`;
    const s = await scheduled(request, version, email, 30);
    await acceptInvitation(page, request, email, secret, s.title);
    await checkInAndStart(page, s.code, s.title);
    await page.getByRole("radio", { name: /Option beta/ }).check();
    await saved(page);
    await page.close();                                                        // the candidate leaves; nothing submits from the browser
    await expect(async () => {
      const r = await staffApi<{ rows: { attemptStatus?: string; scoreStatus?: string; awarded?: number }[] }>(request, s.ctl, "GET", `/api/v1/exam/results/${s.scheduleId}`);
      expect(r.rows[0]?.attemptStatus).toBe("AUTO_SUBMITTED");
      expect(r.rows[0]?.scoreStatus).toBe("PROVISIONAL");
      expect(r.rows[0]?.awarded).toBe(2);
    }).toPass({ timeout: 240_000, intervals: [5_000] });
  });
});
