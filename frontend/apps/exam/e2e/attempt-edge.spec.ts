import { expect, test, type Page, type Request } from "@playwright/test";
import { checkInAndStart, goToQuestion, publishedVersion, registerCandidate, reloadAndSignIn, saveStatus, schedule } from "./exam-fixture";

/**
 * QA (D-7): edge cases of the candidate examination around the main journeys (exam-journey.spec.ts, attempt.spec.ts):
 * repeated offline/online cycles, two tabs on one attempt, a reload while a save is pending, a client clock that is wrong
 * against the server, and a submission while a save is still in flight. Real candidate realm, delivery process and database.
 */
const RUN = Date.now().toString(36);
let version = "";

test.beforeAll(async ({ request }) => {
  test.setTimeout(180_000);
  version = await publishedVersion(request, `${RUN}e`, 30);
});

const secret = () => `Exam-${Date.now()}-pw!`;
const email = (label: string) => `${label}.${RUN}.${Math.random().toString(36).slice(2, 6)}@candidates.test`;

/** Registers, checks in and starts; returns the attempt id. */
async function start(page: Page, request: Parameters<typeof schedule>[0], label: string) {
  const e = email(label);
  const exam = await schedule(request, version, `Examination ${RUN}e`, [e]);
  await registerCandidate(page, exam.key, e, secret());
  const attemptId = await checkInAndStart(page, exam, exam.codes[0]!);
  return { exam, attemptId };
}

/** The candidate's own bearer token, taken from the browser's delivery requests (for direct checks against the server). */
function authOf(page: Page): () => Promise<string> {
  let last: Request | null = null;
  page.on("request", (r) => { if (r.url().includes("/api/v1/delivery/") && r.headers()["authorization"]) last = r; });
  return async () => (await last!.allHeaders())["authorization"]!;
}

async function serverAnswers(page: Page, auth: () => Promise<string>, key: string, attemptId: string) {
  const r = await page.request.get(`/api/v1/delivery/portals/${key}/attempts/${attemptId}`, { headers: { authorization: await auth() } });
  expect(r.status()).toBe(200);
  const v = (await r.json()) as { status: string; answers: { position: number; response: { value: string }; seq: number }[] };
  return { status: v.status, values: Object.fromEntries(v.answers.map((a) => [a.position, a.response.value])) as Record<number, string> };
}

/** After a sign-in the app shows either the attempt itself or the list with "Resume examination"; ends on the attempt. */
async function resume(page: Page, attemptId: string) {
  const question = page.getByRole("heading", { name: /^Question \d of 3$/ });
  const button = page.getByRole("region", { name: "Your examinations" }).getByRole("button", { name: "Resume examination" });
  await expect(question.or(button)).toBeVisible();
  if (await button.isVisible()) await button.click();
  await expect(question).toBeVisible();
  expect(page.url()).toContain(`/attempts/${attemptId}`);
}

test.describe("candidate examination edge cases (QA, D-7)", () => {
  test("repeated offline/online cycles: every answer is saved once the connection returns, none is lost", async ({ page, request }) => {
    test.setTimeout(240_000);
    const auth = authOf(page);
    const { exam, attemptId } = await start(page, request, "cycles");
    const picks: [number, string, string][] = [[1, "Alpha 1", "A"], [2, "Beta 2", "B"], [3, "Gamma 3", "C"], [1, "Gamma 1", "C"]];
    for (const [q, label] of picks) {
      await page.context().setOffline(true);
      await goToQuestion(page, q);
      await page.getByLabel(label).check();
      await expect(saveStatus(page)).toHaveText("Saving your answers…");
      await page.waitForTimeout(1_500);
      await page.context().setOffline(false);
      await expect(saveStatus(page)).toHaveText("All answers saved", { timeout: 15_000 });
    }
    // the server holds the last answer per question (question 1 was changed in the last cycle)
    expect((await serverAnswers(page, auth, exam.key, attemptId)).values).toEqual({ 1: "C", 2: "B", 3: "C" });
  });

  test("two tabs on one attempt: the newest answer wins on the server and a reload shows it in either tab", async ({ page, request, context }) => {
    test.setTimeout(240_000);
    const auth = authOf(page);
    const { exam, attemptId } = await start(page, request, "tabs");
    await page.getByLabel("Alpha 1").check();
    await expect(saveStatus(page)).toHaveText("All answers saved");

    // second tab of the same browser (single sign-on, same candidate), same attempt
    const tab2 = await context.newPage();
    await tab2.goto(`/p/${exam.key}/attempts/${attemptId}`);
    await tab2.getByRole("button", { name: "Sign in" }).click();
    await resume(tab2, attemptId);
    await expect(tab2.getByLabel("Alpha 1")).toBeChecked();         // it sees the first tab's saved answer
    await tab2.getByLabel("Beta 1").check();
    await expect(saveStatus(tab2)).toHaveText("All answers saved");
    expect((await serverAnswers(page, auth, exam.key, attemptId)).values[1]).toBe("B");

    // the first tab still shows its own (older) choice until it reloads; a newer change in it wins again
    await expect(page.getByLabel("Alpha 1")).toBeChecked();
    await page.getByLabel("Gamma 1").check();
    await expect(saveStatus(page)).toHaveText("All answers saved");
    expect((await serverAnswers(page, auth, exam.key, attemptId)).values[1]).toBe("C");
    await reloadAndSignIn(tab2);
    await resume(tab2, attemptId);
    await expect(tab2.getByLabel("Gamma 1")).toBeChecked();
    await tab2.close();
  });

  test("a reload while a save is still pending: the answer kept on the device is sent after the reload", async ({ page, request }) => {
    test.setTimeout(240_000);
    const auth = authOf(page);
    const { exam, attemptId } = await start(page, request, "pending");
    // hold every answer save so it never reaches the server before the reload
    await page.route("**/answers/*", (route) => { if (route.request().method() === "PUT") void route.abort("failed"); else void route.continue(); });
    await goToQuestion(page, 2);
    await page.getByLabel("Gamma 2").check();
    await expect(page.getByText("Not saved yet — retrying automatically")).toBeVisible({ timeout: 10_000 });
    expect((await serverAnswers(page, auth, exam.key, attemptId)).values[2]).toBeUndefined();
    await page.unroute("**/answers/*");

    await reloadAndSignIn(page);
    await resume(page, attemptId);
    await goToQuestion(page, 2);
    await expect(page.getByLabel("Gamma 2")).toBeChecked();
    await expect(saveStatus(page)).toHaveText("All answers saved", { timeout: 15_000 });
    expect((await serverAnswers(page, auth, exam.key, attemptId)).values[2]).toBe("C");
  });

  for (const skewMinutes of [+17, -23]) {
    test(`a device clock ${skewMinutes > 0 ? "ahead of" : "behind"} the server by ${Math.abs(skewMinutes)} minutes does not change the time remaining`, async ({ page, request }) => {
      test.setTimeout(240_000);
      await page.clock.setSystemTime(new Date(Date.now() + skewMinutes * 60_000));
      await start(page, request, `skew${skewMinutes > 0 ? "a" : "b"}`);
      // the page's own clock really is off by the skew
      const deviceOffsetMinutes = ((await page.evaluate(() => Date.now())) - Date.now()) / 60_000;
      expect(Math.round(deviceOffsetMinutes)).toBe(skewMinutes);
      const timer = page.getByRole("timer", { name: "Time remaining" });
      // a 30-minute examination just started: the countdown follows the server's clock, not the device's
      await expect(timer).toHaveText(/^(29|30):\d\d$/);
      await expect(page.getByText("Time is up")).toHaveCount(0);
      await page.getByLabel("Beta 1").check();
      await expect(saveStatus(page)).toHaveText("All answers saved");
      await page.waitForTimeout(2_000);
      await expect(timer).toHaveText(/^(29|30):\d\d$/);
    });
  }

  test("a submission while a save is in flight: the save is either acknowledged before the submission or refused, never lost silently", async ({ page, request }) => {
    test.setTimeout(240_000);
    const auth = authOf(page);
    const { exam, attemptId } = await start(page, request, "inflight");
    await page.getByLabel("Alpha 1").check();
    await expect(saveStatus(page)).toHaveText("All answers saved");

    // the next save is held in the browser for 3 s; meanwhile the attempt is submitted (e.g. from the candidate's other tab)
    let release: () => void = () => undefined;
    const held = new Promise<void>((r) => { release = r; });
    await page.route("**/answers/2", async (route) => { await held; await route.continue(); });
    const ack = page.waitForResponse((r) => r.url().endsWith("/answers/2") && r.request().method() === "PUT");
    await goToQuestion(page, 2);
    await page.getByLabel("Beta 2").check();
    await expect(saveStatus(page)).toHaveText("Saving your answers…");
    // the UI itself does not submit while an answer is unsaved
    await goToQuestion(page, 3);
    await page.getByRole("button", { name: "Finish examination" }).click();
    await expect(page.getByRole("button", { name: "Submit now" })).toBeDisabled();
    const submitted = await page.request.post(`/api/v1/delivery/portals/${exam.key}/attempts/${attemptId}/submit`, {
      headers: { authorization: await auth(), "content-type": "application/json" }, data: { submitKey: "other-tab" } });
    expect(submitted.status()).toBe(200);
    const result = (await submitted.json()) as { answered: number };
    release();
    const late = await ack;
    // the late save is refused (409), so the candidate is never told it was saved; the result counts only question 1
    expect(late.status()).toBe(409);
    expect(result.answered).toBe(1);
    const server = await serverAnswers(page, auth, exam.key, attemptId);
    expect(server.status).toBe("SUBMITTED");
    expect(server.values[2]).toBeUndefined();
    // the page follows the server: the attempt shows as submitted, not as saved
    await expect(page.getByRole("status").filter({ hasText: "Your examination has been submitted." })).toBeVisible({ timeout: 15_000 });
  });
});
