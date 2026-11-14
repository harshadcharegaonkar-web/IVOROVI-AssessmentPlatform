import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Request } from "@playwright/test";
import { checkInAndStart, goToQuestion, publishedVersion, registerCandidate, reloadAndSignIn, saveStatus, schedule } from "./exam-fixture";

/**
 * Failure and recovery (QA, D-7): the delivery process crashes (SIGKILL) in the middle of an attempt and comes back. The
 * answer given while it is down stays on the device, is never shown as saved, and is saved by the automatic retry once the
 * process is back; acknowledged answers survive the crash; the deadline does not move; the attempt can be submitted.
 *
 * Opt-in, because it kills a process of the stack under test: QA_FAILURE_TESTS=1, plus scripts/qa/delivery-process.sh
 * configured for the stack (compose service by default; QA_DELIVERY_MODE=native with QA_DELIVERY_START for a native run).
 */
const SCRIPT = resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../../scripts/qa/delivery-process.sh");
const RUN = Date.now().toString(36);

function delivery(action: "kill" | "start") {
  execFileSync(SCRIPT, [action], { stdio: "inherit", timeout: 200_000 });
}

test.describe("delivery process crash during an attempt (QA, D-7)", () => {
  test.skip(process.env.QA_FAILURE_TESTS !== "1", "failure tests kill a process of the stack: set QA_FAILURE_TESTS=1 to run them");

  test("answers survive a crash of the delivery process, the pending one is saved after the restart", async ({ page, request }) => {
    test.setTimeout(420_000);
    const version = await publishedVersion(request, `${RUN}r`, 30);
    const email = `crash.${RUN}@candidates.test`;
    const exam = await schedule(request, version, `Examination ${RUN}r`, [email]);
    await registerCandidate(page, exam.key, email, `Exam-${Date.now()}-pw!`);
    const gets: Request[] = [];
    page.on("request", (r) => { if (r.method() === "GET" && /\/attempts\/[0-9a-f-]{36}$/.test(r.url())) gets.push(r); });
    const attemptId = await checkInAndStart(page, exam, exam.codes[0]!);
    await page.getByLabel("Beta 1").check();
    await expect(saveStatus(page)).toHaveText("All answers saved");
    const view = async () => {
      const g = gets[gets.length - 1]!;
      const r = await request.get(g.url(), { headers: { authorization: (await g.allHeaders())["authorization"]! } });
      expect(r.status()).toBe(200);
      return (await r.json()) as { deadlineAt: string; answers: { position: number; response: { value: string } }[]; status: string };
    };
    const before = await view();

    delivery("kill");
    await goToQuestion(page, 2);
    await page.getByLabel("Gamma 2").check();
    await expect(saveStatus(page)).toHaveText("Saving your answers…");
    await expect(page.getByText("Not saved yet — retrying automatically")).toBeVisible({ timeout: 15_000 });

    delivery("start");
    await expect(saveStatus(page)).toHaveText("All answers saved", { timeout: 30_000 });

    // the server has both answers, the deadline is unchanged
    const after = await view();
    expect(after.deadlineAt).toBe(before.deadlineAt);
    expect(after.status).toBe("IN_PROGRESS");
    expect(Object.fromEntries(after.answers.map((a) => [a.position, a.response.value]))).toMatchObject({ 1: "B", 2: "C" });

    // a reload after the incident shows the same state, and the attempt can be submitted
    await reloadAndSignIn(page);
    if (!page.url().includes(`/attempts/${attemptId}`)) {
      await page.getByRole("region", { name: "Your examinations" }).getByRole("button", { name: "Resume examination" }).click();
    }
    await expect(page.getByLabel("Beta 1")).toBeChecked();
    await goToQuestion(page, 2);
    await expect(page.getByLabel("Gamma 2")).toBeChecked();
    await goToQuestion(page, 3);
    await page.getByRole("button", { name: "Finish examination" }).click();
    await page.getByRole("button", { name: "Submit now" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Your examination has been submitted." })).toBeVisible();
    await expect(page.getByText("You answered 2 of 3 questions.")).toBeVisible();
  });
});
