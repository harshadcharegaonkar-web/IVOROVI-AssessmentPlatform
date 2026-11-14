import type { User, UserManager } from "oidc-client-ts";
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { AttemptPage, ExamsCard } from "./Exam";
import { ApiError, call, type CheckInResult, type CompensationView, type Me, type PortalInfo, type SessionView } from "./api";
import { CALLBACK_PATH, safeReturnPath } from "./auth";
import { portalKeyFromPath } from "./config";
import { runSystemCheck, type SystemCheck } from "./systemCheck";

const HEARTBEAT_MS = 15_000;

function usePath(): [string, (p: string) => void] {
  const [path, setPath] = useState(window.location.pathname);
  useEffect(() => {
    const on = () => setPath(window.location.pathname);
    window.addEventListener("popstate", on);
    return () => window.removeEventListener("popstate", on);
  }, []);
  const go = useCallback((p: string) => {
    window.history.replaceState(null, "", p);
    setPath(p);
  }, []);
  return [path, go];
}

export function App({ manager }: { manager: UserManager }) {
  const [path, go] = usePath();
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const loaded = (u: User) => setUser(u);
    const unloaded = () => setUser(null);
    manager.events.addUserLoaded(loaded);
    manager.events.addUserUnloaded(unloaded);
    (async () => {
      if (window.location.pathname === CALLBACK_PATH) {
        try {
          const u = await manager.signinRedirectCallback();
          setUser(u);
          go(safeReturnPath((u.state as { returnTo?: string } | undefined)?.returnTo));
        } catch (e) {
          setError(e instanceof Error ? e.message : "Sign-in failed");
          go("/");
        }
      } else {
        setUser(await manager.getUser());
      }
      setReady(true);
    })();
    return () => {
      manager.events.removeUserLoaded(loaded);
      manager.events.removeUserUnloaded(unloaded);
    };
  }, [manager, go]);

  const key = portalKeyFromPath(path);
  const signIn = (extra?: Record<string, string>) =>
    void manager.signinRedirect({ state: { returnTo: key ? `/p/${key}` : "/" }, extraQueryParams: extra });

  return (
    <div className="min-h-screen">
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-4 py-3">
          <span className="font-semibold">Ivorovi Exams</span>
          {user && (
            <button type="button" className="text-sm text-primary underline" onClick={() => void manager.signoutRedirect()}>
              Sign out
            </button>
          )}
        </div>
      </header>
      <main id="main" className="mx-auto max-w-3xl px-4 py-6">
        {error && <Alert tone="danger">{error}</Alert>}
        {!ready ? (
          <p role="status">Loading…</p>
        ) : key ? (
          <Portal portalKey={key} user={user} signIn={signIn} path={path} go={go} />
        ) : (
          <section>
            <h1 className="text-2xl font-semibold">Exam portal</h1>
            <p className="mt-2 text-muted-strong">Open the exam link your institution sent you (it looks like …/p/your-institution).</p>
          </section>
        )}
      </main>
    </div>
  );
}

function Portal({ portalKey, user, signIn, path, go }: { portalKey: string; user: User | null; signIn: (extra?: Record<string, string>) => void; path: string; go: (p: string) => void }) {
  const attemptId = /^\/p\/[a-z0-9-]+\/attempts\/([0-9a-f-]{36})$/.exec(path)?.[1] ?? null;
  const [info, setInfo] = useState<PortalInfo | null>(null);
  const [missing, setMissing] = useState(false);
  useEffect(() => {
    call<PortalInfo>(`/portals/${portalKey}`, null).then(setInfo, () => setMissing(true));
  }, [portalKey]);
  if (missing) return <Alert tone="danger">This exam portal does not exist or is not available. Check the link from your institution.</Alert>;
  if (!info) return <p role="status">Loading…</p>;
  return (
    <>
      <h1 className="text-2xl font-semibold">{info.name}</h1>
      <p className="text-muted-strong">{info.organizationName}</p>
      {!user ? (
        <section className="mt-6 rounded-lg border border-border bg-surface p-5">
          <h2 className="text-lg font-semibold">Sign in to continue</h2>
          <p className="mt-1 text-sm text-muted">Your exam account is separate from any staff or student account of the institution.</p>
          <div className="mt-4 flex flex-wrap gap-3">
            <Button onClick={() => signIn()}>Sign in</Button>
            {info.selfRegistration && (
              <Button variant="secondary" onClick={() => signIn({ prompt: "create" })}>
                Create an exam account
              </Button>
            )}
          </div>
        </section>
      ) : (
        attemptId ? (
          <div className="mt-6"><AttemptPage portalKey={portalKey} token={user.access_token} attemptId={attemptId} leave={() => go(`/p/${portalKey}`)} /></div>
        ) : (
          <Home portalKey={portalKey} user={user} signIn={signIn} open={(id) => go(`/p/${portalKey}/attempts/${id}`)} />
        )
      )}
    </>
  );
}

function Home({ portalKey, user, signIn, open }: { portalKey: string; user: User; signIn: (extra?: Record<string, string>) => void; open: (attemptId: string) => void }) {
  const token = user.access_token;
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [check, setCheck] = useState<SystemCheck | null>(null);

  const reload = useCallback(() => {
    call<Me>(`/portals/${portalKey}/me`, token).then(
      (m) => {
        setMe(m);
        setError(null);
      },
      (e: ApiError) => (e.status === 401 ? signIn() : setError(e)),
    );
  }, [portalKey, token, signIn]);
  useEffect(reload, [reload]);
  useEffect(() => {
    void runSystemCheck().then(setCheck);
  }, []);

  if (error) return <Alert tone="danger">{error.message}</Alert>;
  if (!me) return <p role="status">Loading your account…</p>;
  return (
    <div className="mt-6 space-y-6">
      <Card title="Your account">
        <p>
          {me.displayName ?? me.email} <span className="text-muted">({me.email})</span>
        </p>
      </Card>
      {me.mfa.policy !== "OFF" && <Security me={me} signIn={signIn} />}
      {me.mfa.satisfied && (
        <>
          <SystemCheckCard check={check} rerun={() => void runSystemCheck().then(setCheck)} />
          <ExamsCard key={me.sessions.map((x) => x.checkedInAt ?? "-").join("|")} portalKey={portalKey} token={token} open={open} />
          <Sessions portalKey={portalKey} token={token} sessions={me.sessions} check={check} onChange={reload} />
          {me.compensations.length > 0 && <Compensations portalKey={portalKey} token={token} items={me.compensations} check={check} onChange={reload} />}
        </>
      )}
    </div>
  );
}

function Security({ me, signIn }: { me: Me; signIn: (extra?: Record<string, string>) => void }) {
  const required = me.mfa.policy === "REQUIRED";
  return (
    <Card title="Sign-in security">
      {me.mfa.enrolled ? (
        <p className="text-success-text">An authenticator app protects your account.</p>
      ) : required ? (
        <Alert tone="warning">Your institution requires an authenticator app (for example Google Authenticator or Microsoft Authenticator) before you can continue.</Alert>
      ) : (
        <p className="text-muted-strong">Add an authenticator app for extra protection (optional).</p>
      )}
      <div className="mt-3 flex flex-wrap gap-3">
        {!me.mfa.enrolled && <Button onClick={() => signIn({ kc_action: "CONFIGURE_TOTP" })}>Set up authenticator app</Button>}
        {me.mfa.enrolled && (
          <Button variant="secondary" onClick={() => signIn({ kc_action: "CONFIGURE_RECOVERY_AUTHN_CODES" })}>
            Create recovery codes
          </Button>
        )}
      </div>
      <p className="mt-3 text-sm text-muted">Recovery codes let you sign in once each if you lose your phone. Keep them somewhere safe.</p>
    </Card>
  );
}

function SystemCheckCard({ check, rerun }: { check: SystemCheck | null; rerun: () => void }) {
  return (
    <Card title="System check">
      {!check ? (
        <p role="status">Checking your device…</p>
      ) : (
        <ul className="divide-y divide-border" aria-label="System check results">
          {check.items.map((i) => (
            <li key={i.id} className="flex items-start justify-between gap-4 py-2">
              <span className="font-medium">{i.label}</span>
              <span className={i.ok === false ? "text-danger-text" : i.ok ? "text-success-text" : "text-muted-strong"}>
                <span className="sr-only">{i.ok === false ? "Problem: " : i.ok ? "OK: " : "Note: "}</span>
                {i.detail}
              </span>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3">
        <Button variant="secondary" onClick={rerun}>
          Run again
        </Button>
      </div>
    </Card>
  );
}

function Sessions({ portalKey, token, sessions, check, onChange }: { portalKey: string; token: string; sessions: SessionView[]; check: SystemCheck | null; onChange: () => void }) {
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<{ tone: "success" | "danger"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await call<CheckInResult>(`/portals/${portalKey}/check-ins`, token, {
        method: "POST",
        body: { code, clockOffsetMs: check?.clockOffsetMs ?? null, systemCheck: check?.summary ?? {} },
      });
      setMsg({ tone: "success", text: `Checked in to ${r.sessionName}.` });
      setCode("");
      onChange();
    } catch (err) {
      setMsg({ tone: "danger", text: err instanceof Error ? err.message : "Check-in failed" });
    } finally {
      setBusy(false);
    }
  };
  const checkedIn = sessions.filter((s) => s.checkedInAt);
  return (
    <Card title="Sessions">
      {sessions.length === 0 ? (
        <p className="text-muted-strong">No session is scheduled for you yet.</p>
      ) : (
        <ul className="space-y-2">
          {sessions.map((s) => (
            <li key={s.id} className="rounded border border-border p-3">
              <p className="font-medium">{s.name}</p>
              <p className="text-sm text-muted">
                {fmt(s.startsAt)} – {fmt(s.endsAt)}
              </p>
              {s.checkedInAt ? <Presence portalKey={portalKey} token={token} session={s} /> : <p className="text-sm text-muted-strong">Not checked in</p>}
            </li>
          ))}
        </ul>
      )}
      {sessions.some((s) => !s.checkedInAt) && (
        <form className="mt-4 flex flex-wrap items-end gap-3" onSubmit={(e) => void submit(e)}>
          <label className="flex flex-col text-sm font-medium" htmlFor="code">
            Session code
            <input
              id="code"
              name="code"
              autoComplete="one-time-code"
              inputMode="text"
              autoCapitalize="characters"
              spellCheck={false}
              maxLength={11}
              required
              className="mt-1 w-48 rounded border border-border-strong px-3 py-2 font-mono uppercase tracking-widest"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              aria-describedby="code-help"
            />
          </label>
          <Button type="submit" disabled={busy || code.replace(/[\s-]/g, "").length !== 10}>
            Check in
          </Button>
          <p id="code-help" className="w-full text-sm text-muted">
            10 characters, given by the invigilator or your institution. Each code works once, only during its time window.
          </p>
        </form>
      )}
      {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
      {checkedIn.length > 0 && <p className="mt-3 text-sm text-muted">Keep this page open: it tells the exam service you are present.</p>}
    </Card>
  );
}

function Presence({ portalKey, token, session }: { portalKey: string; token: string; session: SessionView }) {
  const [last, setLast] = useState<{ at: string; beats: number } | null>(null);
  const [lost, setLost] = useState(false);
  const tokenRef = useRef(token);
  useEffect(() => {
    tokenRef.current = token;
  }, [token]);
  useEffect(() => {
    const active = () => Date.now() < Date.parse(session.endsAt);
    const beat = () => {
      if (!active()) return;
      call<{ serverTime: string; beats: number }>(`/portals/${portalKey}/presence`, tokenRef.current, { method: "POST", body: { sessionId: session.id } }).then(
        (r) => {
          setLast({ at: r.serverTime, beats: r.beats });
          setLost(false);
        },
        () => setLost(true),
      );
    };
    beat();
    const t = window.setInterval(beat, HEARTBEAT_MS);
    return () => window.clearInterval(t);
  }, [portalKey, session.id, session.endsAt]);
  return (
    <p className="text-sm" role="status" aria-live="polite">
      <span className="text-success-text">Checked in {fmt(session.checkedInAt!)}.</span>{" "}
      {lost ? <span className="text-danger-text">Connection lost — keep this page open; it reconnects automatically.</span> : last ? <span className="text-muted">Connected (last contact {fmt(last.at)}).</span> : null}
    </p>
  );
}

function Compensations({ portalKey, token, items, check, onChange }: { portalKey: string; token: string; items: CompensationView[]; check: SystemCheck | null; onChange: () => void }) {
  const [msg, setMsg] = useState<string | null>(null);
  const use = async (c: CompensationView) => {
    try {
      await call<CheckInResult>(`/portals/${portalKey}/compensations/${c.id}/check-in`, token, {
        method: "POST",
        body: { clockOffsetMs: check?.clockOffsetMs ?? null, systemCheck: check?.summary ?? {} },
      });
      setMsg("Done: you are checked in again.");
      onChange();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : "Not available");
    }
  };
  return (
    <Card title="Compensation after a service interruption">
      <ul className="space-y-2">
        {items.map((c) => (
          <li key={c.id} className="rounded border border-border p-3">
            <p className="font-medium">{c.sessionName}</p>
            <p className="text-sm text-muted-strong">
              {c.action === "EXTEND_SESSION" ? `${c.extensionMinutes} extra minutes and a new check-in` : "A new check-in"}
              {c.validUntil && `, available until ${fmt(c.validUntil)}`}
            </p>
            {c.usedAt ? <p className="text-sm text-success-text">Used {fmt(c.usedAt)}</p> : <Button onClick={() => void use(c)}>Use now</Button>}
          </li>
        ))}
      </ul>
      {msg && <p role="status" className="mt-2 text-sm">{msg}</p>}
    </Card>
  );
}

// ---------------------------------------------------------------------------------------------- small UI pieces

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-surface p-5" aria-labelledby={slug(title)}>
      <h2 id={slug(title)} className="mb-3 text-lg font-semibold">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Button({ children, onClick, variant = "primary", type = "button", disabled }: { children: ReactNode; onClick?: () => void; variant?: "primary" | "secondary"; type?: "button" | "submit"; disabled?: boolean }) {
  const cls =
    variant === "primary"
      ? "bg-primary text-primary-contrast hover:bg-primary-hover"
      : "border border-border-strong bg-surface text-fg hover:bg-surface-muted";
  return (
    <button type={type} disabled={disabled} onClick={onClick} className={`rounded px-4 py-2 font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-50 ${cls}`}>
      {children}
    </button>
  );
}

function Alert({ tone, children }: { tone: "danger" | "warning" | "success"; children: ReactNode }) {
  const cls = tone === "danger" ? "bg-danger-soft text-danger-text" : tone === "warning" ? "bg-warning-soft text-warning-text" : "bg-success-soft text-success-text";
  return (
    <p role={tone === "danger" ? "alert" : "status"} className={`mt-3 rounded px-3 py-2 text-sm ${cls}`}>
      {children}
    </p>
  );
}

const slug = (s: string) => "h-" + s.toLowerCase().replace(/[^a-z]+/g, "-");
const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
