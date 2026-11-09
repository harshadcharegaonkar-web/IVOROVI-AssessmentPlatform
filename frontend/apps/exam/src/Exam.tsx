import { ContentView, type ContentDoc } from "@ivorovi/content";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ApiError, call } from "./api";

/** Delivery API types (M3). Answers are {"value": …}: option id, list of option ids, boolean or text. */
export interface ExamView {
  scheduleId: string; title: string; opensAt: string; closesAt: string; lateEntryUntil: string | null; durationMinutes: number; extraMinutes: number;
  checkedIn: boolean; attemptStatus: string | null; attemptId: string | null; availability: "NOT_OPEN" | "CHECK_IN_REQUIRED" | "START" | "RESUME" | "FINISHED" | "CLOSED";
}
interface Option { id: string; content: ContentDoc }
interface Item {
  position: number; questionVersionId: string; type: string; content: ContentDoc; marks: number; negativeMarks: number;
  projection: { options?: Option[]; maxSelections?: number; minSelections?: number; unit?: string; precision?: number } | null;
}
interface Answer { position: number; response: { value: unknown }; seq: number; savedAt: string }
interface AttemptView {
  id: string; scheduleId: string; title: string; status: string; startedAt: string; deadlineAt: string; extraMinutes: number; serverTime: string;
  submittedAt: string | null; items: Item[]; answers: Answer[];
}
interface Ack { position: number; seq: number; applied: boolean; savedAt: string; serverTime: string }
interface SubmitResult { attemptId: string; status: string; submittedAt: string; answered: number; questions: number }

type SaveState = "saved" | "saving" | "pending" | "failed";

const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

/** The candidate's scheduled examinations, with what they can do now. */
export function ExamsCard({ portalKey, token, open }: { portalKey: string; token: string; open: (attemptId: string) => void }) {
  const [exams, setExams] = useState<ExamView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const load = useCallback(() => {
    call<ExamView[]>(`/portals/${portalKey}/exams`, token).then(setExams, (e: ApiError) => setError(e.message));
  }, [portalKey, token]);
  useEffect(load, [load]);
  const start = async (e: ExamView) => {
    setBusy(e.scheduleId);
    setError(null);
    try {
      const a = await call<AttemptView>(`/portals/${portalKey}/exams/${e.scheduleId}/attempt`, token, { method: "POST" });
      open(a.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "The examination could not be started.");
      load();
    } finally {
      setBusy(null);
    }
  };
  const note: Record<ExamView["availability"], string> = {
    NOT_OPEN: "Not open yet", CHECK_IN_REQUIRED: "Check in with your session code first", START: "Ready to start", RESUME: "In progress",
    FINISHED: "Submitted", CLOSED: "Entry closed",
  };
  return (
    <section className="rounded-lg border border-border bg-surface p-5" aria-labelledby="h-exams">
      <h2 id="h-exams" className="text-lg font-semibold">Your examinations</h2>
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
      {!exams ? <p role="status" className="mt-2 text-sm">Loading…</p> : exams.length === 0 ? (
        <p className="mt-2 text-sm text-muted">No examination is scheduled for you yet.</p>
      ) : (
        <ul className="mt-3 space-y-3">
          {exams.map((e) => (
            <li key={e.scheduleId} className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="font-medium">{e.title}</p>
                  <p className="text-sm text-muted-strong">{fmt(e.opensAt)} – {fmt(e.closesAt)} · {e.durationMinutes} minutes{e.extraMinutes > 0 ? ` + ${e.extraMinutes} extra` : ""}</p>
                  <p className="text-sm">{note[e.availability]}</p>
                </div>
                {(e.availability === "START" || e.availability === "RESUME") && (
                  <button type="button" disabled={busy === e.scheduleId} onClick={() => void start(e)}
                    className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-60">
                    {e.availability === "START" ? "Start examination" : "Resume examination"}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------------------------------------- attempt

const pendingKey = (attemptId: string) => `ivorovi.answers.${attemptId}`;
type Pending = Record<number, { value: unknown; seq: number }>;

function readPending(attemptId: string): Pending {
  try {
    return JSON.parse(window.localStorage.getItem(pendingKey(attemptId)) ?? "{}") as Pending;
  } catch {
    return {};
  }
}
function writePending(attemptId: string, p: Pending) {
  try {
    if (Object.keys(p).length === 0) window.localStorage.removeItem(pendingKey(attemptId));
    else window.localStorage.setItem(pendingKey(attemptId), JSON.stringify(p));
  } catch {
    /* storage unavailable: saves still go to the server; only offline recovery is lost */
  }
}

/** The examination itself: one question at a time, autosave, server-time countdown, idempotent submission. */
export function AttemptPage({ portalKey, token, attemptId, leave }: { portalKey: string; token: string; attemptId: string; leave: () => void }) {
  const [attempt, setAttempt] = useState<AttemptView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [current, setCurrent] = useState(1);
  const [values, setValues] = useState<Record<number, unknown>>({});
  const [states, setStates] = useState<Record<number, SaveState>>({});
  const [offset, setOffset] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<SubmitResult | null>(null);
  const seqRef = useRef(0);
  const timers = useRef<Record<number, number>>({});
  const reloadRef = useRef<() => Promise<void>>(async () => undefined);
  const sendRef = useRef<(position: number, value: unknown, seq: number) => Promise<void>>(async () => undefined);

  const nextSeq = () => {
    seqRef.current = Math.max(seqRef.current + 1, Date.now());          // increases across reloads too
    return seqRef.current;
  };

  const send = useCallback(async (position: number, value: unknown, seq: number) => {
    setStates((s) => ({ ...s, [position]: "saving" }));
    try {
      const ack = await call<Ack>(`/portals/${portalKey}/attempts/${attemptId}/answers/${position}`, token, { method: "PUT", body: { seq, response: { value } } });
      setOffset(Date.parse(ack.serverTime) - Date.now());
      const p = readPending(attemptId);
      if (p[position] && p[position].seq <= ack.seq) { delete p[position]; writePending(attemptId, p); }
      setStates((s) => ({ ...s, [position]: "saved" }));
    } catch (e) {
      if (e instanceof ApiError && (e.code === "attempt_submitted" || e.code === "time_up")) {
        setStates((s) => ({ ...s, [position]: "failed" }));
        void reloadRef.current();
        return;
      }
      setStates((s) => ({ ...s, [position]: "failed" }));
      window.setTimeout(() => {                                         // keep the answer and try again
        const p = readPending(attemptId);
        if (p[position]) void sendRef.current(position, p[position].value, p[position].seq);
      }, 3000);
    }
  }, [portalKey, token, attemptId]);

  const apply = useCallback((a: AttemptView) => {
    setAttempt(a);
    setOffset(Date.parse(a.serverTime) - Date.now());
    const v: Record<number, unknown> = {};
    for (const ans of a.answers) { v[ans.position] = ans.response?.value; seqRef.current = Math.max(seqRef.current, ans.seq); }
    const pending = readPending(attemptId);
    for (const [pos, p] of Object.entries(pending)) { v[Number(pos)] = p.value; seqRef.current = Math.max(seqRef.current, p.seq); }
    setValues(v);
    if (a.status === "IN_PROGRESS") for (const [pos, p] of Object.entries(pending)) void sendRef.current(Number(pos), p.value, p.seq);
    else writePending(attemptId, {});
  }, [attemptId]);
  const failed = useCallback((e: unknown) => setError(e instanceof ApiError ? e.message : "The examination could not be loaded."), []);
  const reload = useCallback(() => call<AttemptView>(`/portals/${portalKey}/attempts/${attemptId}`, token).then(apply, failed), [portalKey, token, attemptId, apply, failed]);

  useEffect(() => {
    sendRef.current = send;
    reloadRef.current = reload;
  }, [send, reload]);
  useEffect(() => {
    call<AttemptView>(`/portals/${portalKey}/attempts/${attemptId}`, token).then(apply, failed);
  }, [portalKey, token, attemptId, apply, failed]);
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

  const open = attempt?.status === "IN_PROGRESS";
  const remainingMs = attempt ? Date.parse(attempt.deadlineAt) - (now + offset) : 0;
  const timeUp = open && remainingMs <= 0;

  const submit = useCallback(async () => {
    try {
      const r = await call<SubmitResult>(`/portals/${portalKey}/attempts/${attemptId}/submit`, token, { method: "POST", body: { submitKey: `submit-${attemptId}` } });
      writePending(attemptId, {});
      setResult(r);
      setConfirming(false);
      void reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Submission failed. Your answers are saved; try again.");
    }
  }, [portalKey, token, attemptId, reload]);

  // time up: submit (the server also submits on its own when time runs out)
  const submittedOnTimeUp = useRef(false);
  useEffect(() => {
    if (timeUp && !submittedOnTimeUp.current) { submittedOnTimeUp.current = true; void submit(); }
  }, [timeUp, submit]);

  const change = (position: number, value: unknown) => {
    if (!open || timeUp) return;
    setValues((v) => ({ ...v, [position]: value }));
    setStates((s) => ({ ...s, [position]: "pending" }));
    const seq = nextSeq();
    const p = readPending(attemptId);
    p[position] = { value, seq };
    writePending(attemptId, p);
    window.clearTimeout(timers.current[position]);
    timers.current[position] = window.setTimeout(() => void send(position, value, seq), 400);
  };

  const answered = useMemo(() => Object.values(values).filter((v) => v !== null && v !== undefined && v !== "" && !(Array.isArray(v) && v.length === 0)).length, [values]);

  if (error && !attempt) return <p role="alert" className="text-danger">{error}</p>;
  if (!attempt) return <p role="status">Loading the examination…</p>;
  if (!open) {
    return (
      <section className="rounded-lg border border-border bg-surface p-5">
        <h1 className="text-2xl font-semibold">{attempt.title}</h1>
        <p className="mt-2" role="status">
          {attempt.status === "AUTO_SUBMITTED" ? "Time ran out and your examination was submitted." : "Your examination has been submitted."}
          {attempt.submittedAt ? ` (${fmt(attempt.submittedAt)})` : ""}
        </p>
        {result && <p className="mt-1 text-sm text-muted-strong">You answered {result.answered} of {result.questions} questions.</p>}
        <button type="button" className="mt-4 text-sm text-primary underline" onClick={leave}>Back to your examinations</button>
      </section>
    );
  }

  const item = attempt.items[current - 1]!;
  const mins = Math.max(0, Math.floor(remainingMs / 60000));
  const secs = Math.max(0, Math.floor((remainingMs % 60000) / 1000));
  const unsaved = Object.values(states).some((s) => s === "pending" || s === "saving" || s === "failed");
  return (
    <div className="space-y-4">
      <div className="sticky top-0 z-10 flex flex-wrap items-center justify-between gap-2 border-b border-border bg-surface py-2">
        <h1 className="text-lg font-semibold">{attempt.title}</h1>
        <p className={`font-mono text-lg tabular-nums ${remainingMs < 5 * 60000 ? "text-danger" : ""}`} role="timer" aria-live="off" aria-label="Time remaining">
          {timeUp ? "Time is up" : `${mins}:${String(secs).padStart(2, "0")}`}
        </p>
        <p className="text-sm" aria-live="polite">{unsaved ? "Saving your answers…" : "All answers saved"}</p>
      </div>
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}

      <nav aria-label="Questions" className="flex flex-wrap gap-2">
        {attempt.items.map((i) => {
          const v = values[i.position];
          const has = v !== undefined && v !== null && v !== "" && !(Array.isArray(v) && v.length === 0);
          return (
            <button key={i.position} type="button" onClick={() => setCurrent(i.position)} aria-current={i.position === current ? "step" : undefined}
              aria-label={`Question ${i.position}${has ? ", answered" : ""}`}
              className={`h-9 w-9 rounded-md border text-sm ${i.position === current ? "border-primary font-semibold" : "border-border"} ${has ? "bg-primary/10" : ""}`}>
              {i.position}
            </button>
          );
        })}
      </nav>

      <section className="rounded-lg border border-border bg-surface p-5" aria-labelledby="h-question">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="h-question" className="font-semibold">Question {item.position} of {attempt.items.length}</h2>
          <span className="text-sm text-muted-strong">{item.marks} marks{item.negativeMarks > 0 ? ` · −${item.negativeMarks} for a wrong answer` : ""}</span>
        </div>
        <ContentView doc={item.content} imageSrc={() => undefined} className="mt-3 space-y-2" />
        <div className="mt-4"><ResponseInput item={item} value={values[item.position]} disabled={timeUp} onChange={(v) => change(item.position, v)} /></div>
        <p className="mt-2 text-xs text-muted" aria-live="polite">
          {{ saved: "Saved", saving: "Saving…", pending: "Saving…", failed: "Not saved yet — retrying automatically" }[states[item.position] ?? "saved"]}
        </p>
      </section>

      <div className="flex flex-wrap justify-between gap-2">
        <button type="button" disabled={current === 1} onClick={() => setCurrent(current - 1)} className="rounded-md border border-border px-4 py-2 text-sm disabled:opacity-50">Previous</button>
        {current < attempt.items.length
          ? <button type="button" onClick={() => setCurrent(current + 1)} className="rounded-md border border-border px-4 py-2 text-sm">Next</button>
          : <button type="button" onClick={() => setConfirming(true)} className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-white">Finish examination</button>}
      </div>

      {confirming && (
        <section role="alertdialog" aria-labelledby="h-confirm" className="rounded-lg border border-warning bg-surface p-5">
          <h2 id="h-confirm" className="font-semibold">Submit your examination?</h2>
          <p className="mt-1 text-sm">You answered {answered} of {attempt.items.length} questions. After submitting you cannot change any answer.</p>
          {unsaved && <p className="mt-1 text-sm text-danger">Some answers are still being saved. Wait until “All answers saved” appears.</p>}
          <div className="mt-3 flex gap-3">
            <button type="button" disabled={unsaved} onClick={() => void submit()} className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-60">Submit now</button>
            <button type="button" onClick={() => setConfirming(false)} className="rounded-md border border-border px-4 py-2 text-sm">Keep working</button>
          </div>
        </section>
      )}
    </div>
  );
}

function ResponseInput({ item, value, disabled, onChange }: { item: Item; value: unknown; disabled: boolean; onChange: (v: unknown) => void }) {
  const options = item.projection?.options ?? [];
  const name = `q-${item.position}`;
  const wrap = (children: ReactNode, legend: string) => (
    <fieldset disabled={disabled}><legend className="sr-only">{legend}</legend><div className="space-y-2">{children}</div></fieldset>
  );
  switch (item.type) {
    case "SINGLE_CHOICE":
      return wrap(options.map((o) => (
        <label key={o.id} className="flex cursor-pointer gap-3 rounded-md border border-border p-3">
          <input type="radio" name={name} checked={value === o.id} onChange={() => onChange(o.id)} />
          <span className="font-semibold" aria-hidden>{o.id}.</span><ContentView doc={o.content} imageSrc={() => undefined} className="min-w-0 flex-1" />
        </label>)), "Choose one answer");
    case "MULTIPLE_CHOICE": {
      const chosen = Array.isArray(value) ? (value as string[]) : [];
      return wrap(options.map((o) => (
        <label key={o.id} className="flex cursor-pointer gap-3 rounded-md border border-border p-3">
          <input type="checkbox" checked={chosen.includes(o.id)} onChange={(e) => onChange(e.target.checked ? [...chosen, o.id] : chosen.filter((x) => x !== o.id))} />
          <span className="font-semibold" aria-hidden>{o.id}.</span><ContentView doc={o.content} imageSrc={() => undefined} className="min-w-0 flex-1" />
        </label>)), "Choose all answers that apply");
    }
    case "TRUE_FALSE":
      return wrap([true, false].map((b) => (
        <label key={String(b)} className="flex cursor-pointer gap-3 rounded-md border border-border p-3">
          <input type="radio" name={name} checked={value === b} onChange={() => onChange(b)} />{b ? "True" : "False"}
        </label>)), "True or false");
    case "NUMERIC":
      return (
        <label className="block text-sm">Your answer{item.projection?.unit ? ` (${item.projection.unit})` : ""}
          <input type="text" inputMode="decimal" disabled={disabled} value={(value as string) ?? ""} maxLength={60}
            onChange={(e) => onChange(e.target.value)} className="mt-1 block w-48 rounded-md border border-border px-3 py-2" />
        </label>);
    default:
      return (
        <label className="block text-sm">Your answer
          <input type="text" disabled={disabled} value={(value as string) ?? ""} maxLength={1000} onChange={(e) => onChange(e.target.value)}
            className="mt-1 block w-full rounded-md border border-border px-3 py-2" />
        </label>);
  }
}
