import { ApiError, unwrap, type SecurityPolicyView } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, Input, PageHeader, Skeleton, Switch, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";

const KEY = ["tenant-security-policy"];

/** Parses an optional whole-number limit; "" means no limit. */
export function parseLimit(raw: string, min: number, max: number): { value: number | null; error?: string } {
  const t = raw.trim();
  if (t === "") return { value: null };
  if (!/^\d+$/.test(t)) return { value: null, error: "Whole number" };
  const n = Number(t);
  if (n < min || n > max) return { value: null, error: `Between ${min} and ${max}` };
  return { value: n };
}

/** Mandatory module I: the organization security policy for staff sign-ins and sessions. */
export function SecurityPolicyPage() {
  const api = useApi();
  const q = useQuery({ queryKey: KEY, queryFn: async () => unwrap(await api.GET("/api/v1/tenant/security-policy")) });
  return (
    <>
      <PageHeader title="Security Policy" description="Second factor and session limits for staff of your organization." />
      {q.isPending ? (
        <Card><CardBody className="space-y-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-10 w-full" />)}</CardBody></Card>
      ) : q.isError ? (
        <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>
      ) : (
        <PolicyForm key={`${q.data.version}-${q.data.pendingApprovalId ?? ""}`} policy={q.data} />
      )}
    </>
  );
}

function PolicyForm({ policy }: { policy: SecurityPolicyView }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [mfa, setMfa] = useState(policy.mfaRequired ?? false);
  const [idle, setIdle] = useState(policy.idleMinutes == null ? "" : String(policy.idleMinutes));
  const [absolute, setAbsolute] = useState(policy.absoluteHours == null ? "" : String(policy.absoluteHours));
  const [problem, setProblem] = useState<string | null>(null);
  const idleP = parseLimit(idle, 5, 1440);
  const absP = parseLimit(absolute, 1, 720);
  const idleTooLong = idleP.value != null && absP.value != null && idleP.value > absP.value * 60;
  const dirty = mfa !== (policy.mfaRequired ?? false) || idleP.value !== (policy.idleMinutes ?? null) || absP.value !== (policy.absoluteHours ?? null);
  const valid = !idleP.error && !absP.error && !idleTooLong;

  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/tenant/security-policy", {
      body: { mfaRequired: mfa, idleMinutes: idleP.value ?? undefined, absoluteHours: absP.value ?? undefined, version: policy.version ?? 0 },
    })),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: KEY });
      if (r.status === "PENDING_APPROVAL") toast.show("info", "A weaker policy needs a second administrator's approval. It was sent for approval.");
      else if (r.status === "APPLIED") toast.show("success", "Security policy saved");
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === "concurrent_modification") void qc.invalidateQueries({ queryKey: KEY });
      setProblem(errorInfo(e).message);
    },
  });

  const pending = policy.pendingChange;
  return (
    <form noValidate className="space-y-6" onSubmit={(e) => { e.preventDefault(); setProblem(null); save.mutate(); }}>
      {problem && <div data-testid="policy-problem"><Alert tone="danger">{problem}</Alert></div>}
      {policy.pendingApprovalId && pending && (
        <div data-testid="policy-pending"><Alert tone="warning">
          A weaker policy is waiting for approval: second factor {pending.mfaRequired ? "required" : "optional"}, idle limit{" "}
          {pending.idleMinutes == null ? "none" : `${pending.idleMinutes} min`}, session limit {pending.absoluteHours == null ? "none" : `${pending.absoluteHours} h`}.{" "}
          <Link className="underline" to={`/approvals/${policy.pendingApprovalId}`}>View the request</Link>. The current policy stays in force until another
          administrator approves it.
        </Alert></div>
      )}
      <Card>
        <CardHeader title="Second factor" description="Require an authenticator app at every staff sign-in." />
        <CardBody className="space-y-3">
          <div className="flex items-start gap-3">
            <Switch id="mfa" checked={mfa} onCheckedChange={setMfa} label="Require an authenticator app" />
            <label htmlFor="mfa" className="text-sm">
              <span className="block font-medium text-fg">Require an authenticator app</span>
              <span className="block text-muted-strong">
                Staff who have not set one up are asked to do so at their next sign-in. Until then they cannot use Ivorovi.
              </span>
            </label>
          </div>
          {mfa && !policy.mfaRequired && !policy.callerHasSecondFactor && (
            <div data-testid="mfa-self-first"><Alert tone="info">
              Set up an authenticator app for your own account first. Saving will start that for you; sign in again, complete the set-up step, then turn
              this on. This prevents every administrator from being locked out.
            </Alert></div>
          )}
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Session limits" description="Leave a field empty for no limit. Ended sessions require a new sign-in." />
        <CardBody>
          <div className="grid max-w-xl grid-cols-1 gap-4 md:grid-cols-2">
            <Field label="Idle limit (minutes)" hint="5–1440. Signs a user out after this long without activity."
              error={idleP.error ?? (idleTooLong ? "Cannot be longer than the session limit" : undefined)}>
              {(p) => <Input {...p} inputMode="numeric" value={idle} onChange={(e) => setIdle(e.target.value)} data-testid="idle-minutes" />}
            </Field>
            <Field label="Session limit (hours)" hint="1–720. Maximum time from sign-in, whatever the activity." error={absP.error}>
              {(p) => <Input {...p} inputMode="numeric" value={absolute} onChange={(e) => setAbsolute(e.target.value)} data-testid="absolute-hours" />}
            </Field>
          </div>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Password policy" description="Set for the whole platform by the platform operator; it is the same for every organization." />
        <CardBody>
          <p className="text-sm text-fg" data-testid="password-policy">
            Password rules are part of the sign-in service shared by all organizations on this platform, so they cannot differ per organization.
            Contact your platform operator to review or change them.
          </p>
        </CardBody>
      </Card>
      <p className="text-sm text-muted-strong">
        Stronger rules apply at once. Anything weaker (turning the second factor off, a longer or removed limit) is applied only after another
        administrator approves it.
      </p>
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="secondary" disabled={!dirty || save.isPending}
          onClick={() => { setMfa(policy.mfaRequired ?? false); setIdle(policy.idleMinutes == null ? "" : String(policy.idleMinutes));
            setAbsolute(policy.absoluteHours == null ? "" : String(policy.absoluteHours)); setProblem(null); }}>Discard changes</Button>
        <Button type="submit" loading={save.isPending} disabled={!dirty || !valid} data-testid="save-policy">Save policy</Button>
      </div>
    </form>
  );
}

export default SecurityPolicyPage;
