import { expect, test, type Request } from "@playwright/test";
import { checkIn, checkInAndStart, expectNoAnswerInformation, goToQuestion, publishedVersion, recordDelivery, registerCandidate, saveStatus as status, schedule } from "./exam-fixture";

/**
 * QA (D-7): the candidate examination in a real browser against the real candidate realm, delivery process, staff API,
 * database and Mailpit. Sign-in, check-in with a session code, start, autosave with server acknowledgement, recovery after a
 * page reload and after a network interruption, the server-time countdown and automatic submission, candidate submission
 * and duplicate submission. The fixture is built through the staff API only (exam-fixture.ts).
 */
const RUN = Date.now().toString(36);
let thirtyMinutes = "";
let oneMinute = "";

test.beforeAll(async ({ request }) => {
  test.setTimeout(180_000);
  thirtyMinutes = await publishedVersion(request, `${RUN}a`, 30);
  oneMinute = await publishedVersion(request, `${RUN}b`, 1);
});

const secret = () => `Exam-${Date.now()}-pw!`;
const newEmail = (label: string) => `${label}.${RUN}.${Math.random().toString(36).slice(2, 6)}@candidates.test`;

test.describe("candidate examination (QA, D-7)", () => {
  test("KI-85: after checking in, the examination can be started without reloading the page", async ({ page, request }) => {
    test.setTimeout(180_000);
    const email = newEmail("ki85");
    const exam = await schedule(request, thirtyMinutes, `Examination ${RUN}a`, [email]);
    await registerCandidate(page, exam.key, email, secret());
    const exams = page.getByRole("region", { name: "Your examinations" });
    await expect(exams.getByText("Check in with your session code first")).toBeVisible();
    await checkIn(page, exam, exam.codes[0]!);
    // intended: the list follows the check-in (today it still says "Check in with your session code first" until a reload)
    await expect(exams.getByRole("button", { name: "Start examination" })).toBeVisible({ timeout: 10_000 });
  });

  test("sign in, check in, start, autosave with acknowledgement, and recovery after a page reload", async ({ page, request }) => {
    test.setTimeout(240_000);
    const email = newEmail("reload");
    const exam = await schedule(request, thirtyMinutes, `Examination ${RUN}a`, [email]);
    const bodies = recordDelivery(page);
    await registerCandidate(page, exam.key, email, secret());
    const attemptId = await checkInAndStart(page, exam, exam.codes[0]!);

    // answer two questions; each is acknowledged by the server before "All answers saved" appears
    const acks: string[] = [];
    page.on("response", (r) => { if (r.request().method() === "PUT" && r.url().includes("/answers/")) acks.push(`${r.status()}`); });
    await page.getByLabel("Beta 1").check();
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    await goToQuestion(page, 2);
    await page.getByLabel("Gamma 2").check();
    await expect(status(page)).toHaveText("All answers saved");
    expect(acks.length).toBeGreaterThanOrEqual(2);
    expect(acks.every((s) => s === "200")).toBeTruthy();

    // a full reload (tokens live only in memory: single sign-on brings the candidate back without a password)
    await page.reload();
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText(email)).toBeVisible();
    if (!page.url().includes(`/attempts/${attemptId}`)) {
      await page.getByRole("region", { name: "Your examinations" }).getByRole("button", { name: "Resume examination" }).click();
    }
    await expect(page.getByRole("heading", { name: /^Question 1 of 3$/ })).toBeVisible();
    await expect(page.getByLabel("Beta 1")).toBeChecked();
    await goToQuestion(page, 2);
    await expect(page.getByLabel("Gamma 2")).toBeChecked();
    await expect(page.getByRole("button", { name: "Question 3" })).toBeVisible();   // unanswered stays unanswered
    expectNoAnswerInformation(bodies);
  });

  test("a network interruption: the answer is kept, shown as not yet saved, and saved once the connection is back", async ({ page, request }) => {
    test.setTimeout(240_000);
    const email = newEmail("offline");
    const exam = await schedule(request, thirtyMinutes, `Examination ${RUN}a`, [email]);
    await registerCandidate(page, exam.key, email, secret());
    await checkInAndStart(page, exam, exam.codes[0]!);
    await page.getByLabel("Alpha 1").check();
    await expect(status(page)).toHaveText("All answers saved");

    await page.context().setOffline(true);
    await goToQuestion(page, 2);
    await page.getByLabel("Beta 2").check();
    // never shown as saved while only the device has it
    await expect(status(page)).toHaveText("Saving your answers…");
    await expect(page.getByText("Not saved yet — retrying automatically")).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(4_000);
    await expect(status(page)).toHaveText("Saving your answers…");
    // submission is held back while an answer is unsaved
    await goToQuestion(page, 3);
    await page.getByRole("button", { name: "Finish examination" }).click();
    await expect(page.getByText("Some answers are still being saved.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit now" })).toBeDisabled();
    await page.getByRole("button", { name: "Keep working" }).click();

    await page.context().setOffline(false);
    await expect(status(page)).toHaveText("All answers saved", { timeout: 15_000 });

    // the server has it: reload, sign in again (single sign-on), the answer is there
    await page.reload();
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText(email)).toBeVisible();
    if (!page.url().includes("/attempts/")) {
      await page.getByRole("region", { name: "Your examinations" }).getByRole("button", { name: "Resume examination" }).click();
    }
    await goToQuestion(page, 2);
    await expect(page.getByLabel("Beta 2")).toBeChecked();
  });

  test("submission: a double click and a replayed request give one submission with the same result", async ({ page, request }) => {
    test.setTimeout(240_000);
    const email = newEmail("submit");
    const exam = await schedule(request, thirtyMinutes, `Examination ${RUN}a`, [email]);
    const bodies = recordDelivery(page);
    await registerCandidate(page, exam.key, email, secret());
    await checkInAndStart(page, exam, exam.codes[0]!);
    await page.getByLabel("Beta 1").check();
    await expect(status(page)).toHaveText("All answers saved");
    await goToQuestion(page, 3);
    await page.getByRole("button", { name: "Finish examination" }).click();
    await expect(page.getByText("You answered 1 of 3 questions.")).toBeVisible();

    const submits: Request[] = [];
    const results: string[] = [];
    page.on("request", (r) => { if (r.method() === "POST" && r.url().endsWith("/submit")) submits.push(r); });
    page.on("response", (r) => { if (r.request().method() === "POST" && r.url().endsWith("/submit")) void r.text().then((t) => results.push(`${r.status()} ${t}`)); });
    await page.getByRole("button", { name: "Submit now" }).dblclick();
    await expect(page.getByRole("status").filter({ hasText: "Your examination has been submitted." })).toBeVisible();
    await expect(page.getByText("You answered 1 of 3 questions.")).toBeVisible();
    expect(submits.length).toBeGreaterThanOrEqual(1);
    await expect.poll(() => results.length).toBe(submits.length);
    expect(new Set(results).size, results.join("\n")).toBe(1);
    expect(results[0]!).toMatch(/^200 /);

    // replay the browser's own submission request: the same result, no second submission
    const original = submits[0]!;
    const replay = await request.fetch(original.url(), { method: "POST", headers: { authorization: (await original.allHeaders())["authorization"]!, "content-type": "application/json" },
      data: original.postData() ?? "{}" });
    expect(`${replay.status()} ${await replay.text()}`).toBe(results[0]);
    // a different submission key cannot submit again either: same attempt, same submission time
    const other = await request.fetch(original.url(), { method: "POST", headers: { authorization: (await original.allHeaders())["authorization"]!, "content-type": "application/json" },
      data: { submitKey: "another-key" } });
    expect(other.status()).toBe(200);
    expect(((await other.json()) as { submittedAt: string }).submittedAt).toBe((JSON.parse(results[0]!.slice(4)) as { submittedAt: string }).submittedAt);
    // and no answer can be saved afterwards
    const save = await request.fetch(original.url().replace(/\/submit$/, "/answers/2"), { method: "PUT",
      headers: { authorization: (await original.allHeaders())["authorization"]!, "content-type": "application/json" }, data: { seq: Date.now(), response: { value: "B" } } });
    expect(save.status()).toBe(409);
    // back on the list, the examination is finished
    await page.getByRole("button", { name: "Back to your examinations" }).click();
    await expect(page.getByRole("region", { name: "Your examinations" }).getByText("Submitted")).toBeVisible();
    expectNoAnswerInformation(bodies);
  });

  test("timing: the server-time countdown runs out and the attempt is submitted automatically", async ({ page, request }) => {
    test.setTimeout(300_000);
    const email = newEmail("timing");
    const exam = await schedule(request, oneMinute, `Examination ${RUN}b`, [email], 30);
    await registerCandidate(page, exam.key, email, secret());
    await checkInAndStart(page, exam, exam.codes[0]!);
    const timer = page.getByRole("timer", { name: "Time remaining" });
    await expect(timer).toHaveText(/^0:[0-5]\d$/);
    const first = await timer.innerText();
    await page.getByLabel("Beta 1").check();
    await expect(status(page)).toHaveText("All answers saved");
    await page.waitForTimeout(3_000);
    expect(await timer.innerText()).not.toBe(first);   // it counts down

    // capture an answer request to try a late save after the end
    const saves: Request[] = [];
    page.on("request", (r) => { if (r.method() === "PUT" && r.url().includes("/answers/")) saves.push(r); });
    await goToQuestion(page, 2);
    await page.getByLabel("Alpha 2").check();
    await expect(status(page)).toHaveText("All answers saved");

    await expect(page.getByText(/^(Time ran out and your examination was submitted\.|Your examination has been submitted\.)/)).toBeVisible({ timeout: 120_000 });
    const late = saves[0]!;
    const r = await request.fetch(late.url(), { method: "PUT", headers: { authorization: (await late.allHeaders())["authorization"]!, "content-type": "application/json" },
      data: { seq: Date.now(), response: { value: "C" } } });
    expect(r.status()).toBe(409);
    await page.getByRole("button", { name: "Back to your examinations" }).click();
    await expect(page.getByRole("region", { name: "Your examinations" }).getByText("Submitted")).toBeVisible();
  });
});
