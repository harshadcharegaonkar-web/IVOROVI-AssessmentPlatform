import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, createApiClient, unwrap } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, Field, Input, Spinner } from "@ivorovi/ui";
import { GraduationCap } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";

/**
 * Public invitation acceptance (no sign-in). The token arrives in the URL fragment (#token=…), which browsers never
 * send to servers; it is read once and removed from the address bar. Passwords go only to the API, which hands
 * them to the identity provider.
 */
function readToken(): string | null {
  const m = /(?:^|[#&])token=([A-Za-z0-9_-]{43})(?:&|$)/.exec(window.location.hash);
  return m ? m[1]! : null;
}

const schema = z.object({
  password: z.string().min(12, "Use at least 12 characters").max(256),
  confirm: z.string(),
}).refine((v) => v.password === v.confirm, { path: ["confirm"], message: "The passwords do not match" });
type Values = z.infer<typeof schema>;

type Lookup = { organizationName?: string; email?: string; givenName?: string; expiresAt?: string; existingLogin?: boolean };

export function AcceptInvitationPage({ apiBaseUrl }: { apiBaseUrl: string }) {
  const api = useMemo(() => createApiClient({ baseUrl: apiBaseUrl, getAccessToken: async () => undefined, onUnauthorized: () => undefined }), [apiBaseUrl]);
  const [token] = useState(readToken);
  const [state, setState] = useState<{ kind: "loading" } | { kind: "invalid"; message: string } | { kind: "ready"; info: Lookup } | { kind: "done"; info: Lookup }>(
    token ? { kind: "loading" } : { kind: "invalid", message: "This page needs the link from your invitation email." });
  const [problem, setProblem] = useState<string | null>(null);
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { password: "", confirm: "" } });

  useEffect(() => {
    if (window.location.hash) history.replaceState(null, "", window.location.pathname);
    if (!token) return;
    void (async () => {
      try {
        const info = unwrap(await api.POST("/api/v1/public/invitations/lookup", { body: { token } }));
        setState({ kind: "ready", info });
      } catch (e) {
        setState({ kind: "invalid", message: e instanceof ApiError ? e.message : "The invitation could not be checked. Try again later." });
      }
    })();
  }, [api, token]);

  const submit = async (password: string | undefined) => {
    if (state.kind !== "ready" || !token) return;
    setProblem(null);
    try {
      unwrap(await api.POST("/api/v1/public/invitations/accept", { body: { token, ...(password ? { password } : {}) } }));
      setState({ kind: "done", info: state.info });
    } catch (e) {
      if (e instanceof ApiError && (e.code === "password_rejected" || e.code === "weak_password")) {
        form.setError("password", { message: e.message });
        return;
      }
      setProblem(e instanceof ApiError ? e.message : "Something went wrong. Try again.");
    }
  };

  return (
    <main className="flex min-h-screen items-start justify-center bg-bg px-4 py-10 sm:items-center">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center gap-3">
          <span aria-hidden className="flex h-10 w-10 items-center justify-center rounded-lg bg-sidebar text-white"><GraduationCap className="h-5 w-5" /></span>
          <div>
            <p className="text-base font-bold tracking-wide text-fg">IVOROVI</p>
            <p className="text-xs text-muted-strong">Assessment Platform</p>
          </div>
        </div>
        <Card>
          <CardBody className="space-y-5 p-6">
            {state.kind === "loading" && <div className="flex justify-center py-8"><Spinner label="Checking your invitation" /></div>}
            {state.kind === "invalid" && (
              <>
                <h1 className="text-xl font-semibold text-fg">Invitation not valid</h1>
                <Alert tone="warning">{state.message}</Alert>
                <p className="text-sm text-muted">Ask your administrator to send a new invitation.</p>
              </>
            )}
            {state.kind === "ready" && (
              <>
                <div>
                  <h1 className="text-xl font-semibold text-fg">Welcome, {state.info.givenName}</h1>
                  <p className="mt-1 text-sm text-muted">You have been invited to <strong className="text-fg">{state.info.organizationName}</strong>.</p>
                </div>
                <dl className="rounded-lg bg-bg px-4 py-3 text-sm">
                  <dt className="text-muted-strong">Sign-in email</dt>
                  <dd className="font-medium text-fg" data-testid="invitation-email">{state.info.email}</dd>
                </dl>
                {problem && <Alert tone="danger">{problem}</Alert>}
                {state.info.existingLogin ? (
                  <>
                    <p className="text-sm text-muted">You already have an Ivorovi login with this email. Accept to add this account to it, then sign in with your existing password.</p>
                    <Button className="w-full" onClick={() => void submit(undefined)}>Accept invitation</Button>
                  </>
                ) : (
                  <form noValidate className="space-y-4" onSubmit={form.handleSubmit((v) => submit(v.password))}>
                    <Field label="Choose a password" hint="At least 12 characters; not your email address." error={form.formState.errors.password?.message}>
                      {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register("password")} />}
                    </Field>
                    <Field label="Confirm password" error={form.formState.errors.confirm?.message}>
                      {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register("confirm")} />}
                    </Field>
                    <Button type="submit" className="w-full" loading={form.formState.isSubmitting}>Activate account</Button>
                  </form>
                )}
                {state.info.expiresAt && <p className="text-xs text-muted">This invitation expires on {new Date(state.info.expiresAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}.</p>}
              </>
            )}
            {state.kind === "done" && (
              <>
                <h1 className="text-xl font-semibold text-fg">Your account is ready</h1>
                <Alert tone="success">You can now sign in to {state.info.organizationName} with {state.info.email}.</Alert>
                <Button className="w-full" onClick={() => window.location.assign("/")}>Sign in</Button>
              </>
            )}
          </CardBody>
        </Card>
      </div>
    </main>
  );
}

export default AcceptInvitationPage;
