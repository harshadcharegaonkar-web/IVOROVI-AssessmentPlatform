import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type RoleAssignment, type UserDetail } from "@ivorovi/api-client";
import { Alert, Button, ConfirmDialog, Field, Input, Modal, Select, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useOrgTree, type OrgNode } from "../../session/useOrgTree";
import { STATUS_ACTION_LABEL } from "./peopleModel";

export function invalidatePeople(qc: ReturnType<typeof useQueryClient>) {
  for (const key of ["users", "user", "staff", "staff-profile", "invitations"]) void qc.invalidateQueries({ queryKey: [key] });
}

/** Units the caller may choose, indented by depth; "Whole organization" only with the tenant-wide permission. */
export function UnitOptions({ permission, allowOrganization = true }: { permission: string; allowOrganization?: boolean }) {
  const tree = useOrgTree();
  const labels = useOrgLabels();
  const { access } = useSession();
  const options = useMemo(() => {
    const nodes = (tree.data ?? []).filter((n) => n.status === "ACTIVE");
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const depth = (n: OrgNode): number => (n.parentId && byId.has(n.parentId) ? 1 + depth(byId.get(n.parentId)!) : 0);
    const ancestors = (n: OrgNode): string[] => (n.parentId && byId.has(n.parentId) ? [...ancestors(byId.get(n.parentId)!), n.parentId] : []);
    return nodes.filter((n) => access.hasInOrgUnit(permission, n.id, ancestors(n))).map((n) => ({ n, d: depth(n) }));
  }, [tree.data, access, permission]);
  return (
    <>
      {allowOrganization && access.has(permission) && <option value="">Whole organization</option>}
      {options.map(({ n, d }) => (
        <option key={n.id} value={n.id}>{" ".repeat(d * 2)}{n.name} ({labels.singular(n.type)})</option>
      ))}
    </>
  );
}

// ---------------------------------------------------------------------------------------------- user form

const userSchema = z.object({
  email: z.string().trim().min(1, "Email is required").max(320).email("Enter a valid email address"),
  givenName: z.string().trim().min(1, "First name is required").max(100),
  familyName: z.string().trim().max(100),
  phone: z.string().trim().max(30).refine((v) => !v || /^\+?[0-9 ()-]{6,30}$/.test(v), "Enter a valid phone number"),
  orgUnitId: z.string(),
  title: z.string().trim().max(100),
  sendInvitation: z.boolean(),
});
type UserValues = z.infer<typeof userSchema>;

export function UserFormDialog({ open, onOpenChange, existing, defaultUnitId }: {
  open: boolean; onOpenChange: (o: boolean) => void; existing?: UserDetail; defaultUnitId?: string;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const { access } = useSession();
  const user = existing?.user;
  const editing = !!user;
  const idempotencyKey = useRef(crypto.randomUUID());
  const [problem, setProblem] = useState<string | null>(null);
  const form = useForm<UserValues>({
    resolver: zodResolver(userSchema),
    defaultValues: {
      email: user?.email ?? "", givenName: user?.givenName ?? "", familyName: user?.familyName ?? "", phone: user?.phone ?? "",
      orgUnitId: defaultUnitId ?? "", title: "", sendInvitation: true,
    },
  });
  const save = useMutation({
    mutationFn: async (v: UserValues) => {
      if (editing) {
        return unwrap(await api.PATCH("/api/v1/users/{id}", { params: { path: { id: user!.id! } },
          body: { version: user!.version!, givenName: v.givenName, familyName: v.familyName, phone: v.phone,
            ...(user!.status === "INVITED" ? { email: v.email } : {}) } })).user?.id;
      }
      const r = unwrap(await api.POST("/api/v1/users", { headers: { "Idempotency-Key": idempotencyKey.current },
        body: { email: v.email, givenName: v.givenName, familyName: v.familyName, phone: v.phone, title: v.title,
          sendInvitation: v.sendInvitation, ...(v.orgUnitId ? { orgUnitId: v.orgUnitId } : {}) } }));
      return r.user?.user?.id;
    },
    onSuccess: (id, v) => {
      invalidatePeople(qc);
      toast.show("success", editing ? "Changes saved" : v.sendInvitation ? "User created and invited" : "User created",
        editing ? undefined : v.sendInvitation ? `An invitation was sent to ${v.email}.` : "Send the invitation when the user should get access.");
      onOpenChange(false);
      if (!editing && id) void navigate(`/people/users/${id}`);
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        idempotencyKey.current = crypto.randomUUID();
        if (e.code === "email_in_use" || e.code === "invalid_email" || e.code === "email_locked") { form.setError("email", { message: e.message }); return; }
        if (e.code === "invalid_org_unit") { form.setError("orgUnitId", { message: e.message }); return; }
      }
      setProblem(errorInfo(e).message);
    },
  });
  const err = form.formState.errors;
  const emailLocked = editing && user!.status !== "INVITED";
  return (
    <Modal open={open} onOpenChange={onOpenChange} title={editing ? "Edit user" : "New user"}
      description={editing ? user!.displayName : "Create an account and invite the person to sign in."}
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={save.isPending}>Cancel</Button>
        <Button type="submit" form="user-form" loading={save.isPending}>{editing ? "Save changes" : "Create user"}</Button>
      </>}>
      <form id="user-form" noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2"
        onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })}>
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="First name" error={err.givenName?.message}>{(p) => <Input {...p} autoComplete="off" {...form.register("givenName")} />}</Field>
        <Field label="Last name" error={err.familyName?.message}>{(p) => <Input {...p} autoComplete="off" {...form.register("familyName")} />}</Field>
        <Field label="Email" className="sm:col-span-2" error={err.email?.message}
          hint={emailLocked ? "Managed by the sign-in service after the first sign-in." : "Used to sign in; stored in lower case."}>
          {(p) => <Input {...p} type="email" autoComplete="off" disabled={emailLocked} {...form.register("email")} />}
        </Field>
        <Field label="Phone" error={err.phone?.message}>{(p) => <Input {...p} type="tel" {...form.register("phone")} />}</Field>
        {!editing && (
          <>
            <Field label="Title" error={err.title?.message} hint="e.g. Assistant Registrar">{(p) => <Input {...p} {...form.register("title")} />}</Field>
            <Field label="Belongs to" className="sm:col-span-2" error={err.orgUnitId?.message} hint="Primary organizational assignment; more can be added later.">
              {(p) => <Select {...p} {...form.register("orgUnitId")}><UnitOptions permission="user.manage" allowOrganization={access.has("user.manage")} /></Select>}
            </Field>
            <label className="flex items-start gap-3 rounded-lg border border-border p-3 text-sm sm:col-span-2">
              <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--color-primary)]" {...form.register("sendInvitation")} />
              <span><span className="font-medium text-fg">Send invitation email now</span>
                <span className="block text-muted">The link is valid for 7 days and can be used once.</span></span>
            </label>
          </>
        )}
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------------------- status

const DONE = { SUSPEND: "Account suspended", REACTIVATE: "Account reactivated", DEACTIVATE: "Account deactivated", ACTIVATE: "Account activated" } as const;

export function UserStatusDialog({ open, onOpenChange, detail, action }: {
  open: boolean; onOpenChange: (o: boolean) => void; detail: UserDetail; action: keyof typeof STATUS_ACTION_LABEL;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const user = detail.user!;
  const needsReason = action === "SUSPEND" || action === "DEACTIVATE";
  const change = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/users/{id}/status", {
      params: { path: { id: user.id! } }, body: { action, version: user.version!, ...(reason.trim() ? { reason: reason.trim() } : {}) } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", DONE[action], user.displayName); onOpenChange(false); },
    onError: (e) => setError(errorInfo(e).message),
  });
  const text: Record<typeof action, string> = {
    SUSPEND: "The user is signed out everywhere and cannot sign in until reactivated. Assignments and roles are kept.",
    REACTIVATE: "The user can sign in again with the same roles and assignments.",
    DEACTIVATE: "Use when the person has left. Sign-in is disabled, open invitations are revoked; the history is kept.",
    ACTIVATE: "The account becomes usable again. A user who never signed in returns to the invited state.",
  };
  return (
    <ConfirmDialog open={open} onOpenChange={onOpenChange} title={`${STATUS_ACTION_LABEL[action]} ${user.displayName}?`}
      tone={needsReason ? "danger" : "primary"} confirmLabel={STATUS_ACTION_LABEL[action]} busy={change.isPending}
      onConfirm={() => { if (needsReason && !reason.trim()) { setError("Give a reason; it is recorded in the account history."); return; } setError(null); change.mutate(); }}
      description={<p>{text[action]}</p>}>
      <div className="mt-4 space-y-3">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={needsReason ? "Reason (required, recorded in the audit log)" : "Reason (optional)"}>
          {(p) => <TextArea {...p} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </Field>
      </div>
    </ConfirmDialog>
  );
}

// ---------------------------------------------------------------------------------------------- roles

export function AssignRoleDialog({ open, onOpenChange, userId }: { open: boolean; onOpenChange: (o: boolean) => void; userId: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { access } = useSession();
  const roles = useQuery({ queryKey: ["roles"], staleTime: 5 * 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/roles")) });
  const [roleId, setRoleId] = useState("");
  const [unitId, setUnitId] = useState(access.has("role.assign") ? "" : "unset");
  const [validTo, setValidTo] = useState("");
  const [error, setError] = useState<string | null>(null);
  const role = roles.data?.find((r) => r.id === roleId);
  const assign = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/users/{id}/role-assignments", { params: { path: { id: userId } },
      body: { roleId, ...(unitId ? { orgUnitId: unitId } : {}), ...(validTo ? { validTo: new Date(`${validTo}T23:59:59`).toISOString() } : {}) } })),
    onSuccess: (r) => { invalidatePeople(qc); toast.show("success", "Role assigned", `${r.roleName} · ${r.scopeName}`); onOpenChange(false); },
    onError: (e) => setError(errorInfo(e).message),
  });
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Assign role"
      description="You can assign roles only within your own scope and with permissions you hold yourself."
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={assign.isPending}>Cancel</Button>
        <Button loading={assign.isPending} disabled={!roleId || unitId === "unset"} onClick={() => { setError(null); assign.mutate(); }}>Assign</Button>
      </>}>
      <div className="space-y-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Role">
          {(p) => (
            <Select {...p} value={roleId} onChange={(e) => setRoleId(e.target.value)}>
              <option value="">Choose a role…</option>
              {(roles.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </Select>
          )}
        </Field>
        {role && (
          <div className="rounded-lg bg-bg p-3 text-sm">
            <p className="text-muted">{role.description}</p>
            <p className="mt-2 text-xs font-medium uppercase tracking-wider text-muted">Permissions ({role.permissions?.length ?? 0})</p>
            <p className="mt-1 flex flex-wrap gap-1">{(role.permissions ?? []).map((p) => <code key={p} className="rounded bg-surface px-1.5 py-0.5 text-xs">{p}</code>)}</p>
          </div>
        )}
        <Field label="Scope" hint="The role applies to this unit and everything below it.">
          {(p) => (
            <Select {...p} value={unitId} onChange={(e) => setUnitId(e.target.value)}>
              {!access.has("role.assign") && <option value="unset">Choose a unit…</option>}
              <UnitOptions permission="role.assign" />
            </Select>
          )}
        </Field>
        <Field label="Ends on (optional)" hint="Leave empty for an open-ended assignment.">
          {(p) => <Input {...p} type="date" value={validTo} min={new Date().toISOString().slice(0, 10)} onChange={(e) => setValidTo(e.target.value)} />}
        </Field>
      </div>
    </Modal>
  );
}

export function RevokeRoleDialog({ open, onOpenChange, userId, assignment }: {
  open: boolean; onOpenChange: (o: boolean) => void; userId: string; assignment: RoleAssignment;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const revoke = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/users/{id}/role-assignments/{assignmentId}/revoke", {
      params: { path: { id: userId, assignmentId: assignment.id! } }, body: { reason: reason.trim() } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", "Role revoked", `${assignment.roleName} · ${assignment.scopeName}`); onOpenChange(false); },
    onError: (e) => setError(errorInfo(e).message),
  });
  return (
    <ConfirmDialog open={open} onOpenChange={onOpenChange} title={`Revoke ${assignment.roleName}?`} tone="danger" confirmLabel="Revoke"
      busy={revoke.isPending} description={<p>Access granted by this role at <strong>{assignment.scopeName}</strong> ends immediately. The assignment stays in the history.</p>}
      onConfirm={() => { if (!reason.trim()) { setError("Give a reason; it is recorded in the history."); return; } setError(null); revoke.mutate(); }}>
      <div className="mt-4 space-y-3">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Reason (required)">{(p) => <TextArea {...p} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      </div>
    </ConfirmDialog>
  );
}

// ---------------------------------------------------------------------------------------------- org assignment

export function OrgAssignmentDialog({ open, onOpenChange, subject, id }: {
  open: boolean; onOpenChange: (o: boolean) => void; subject: "users" | "staff"; id: string;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const permission = subject === "users" ? "user.manage" : "staff.manage";
  const [unitId, setUnitId] = useState("");
  const [title, setTitle] = useState("");
  const [primary, setPrimary] = useState(false);
  const [startsOn, setStartsOn] = useState("");
  const [endsOn, setEndsOn] = useState("");
  const [error, setError] = useState<string | null>(null);
  const add = useMutation({
    mutationFn: async () => {
      const body = { ...(unitId ? { orgUnitId: unitId } : {}), title, primary, ...(startsOn ? { startsOn } : {}), ...(endsOn ? { endsOn } : {}) };
      return subject === "users"
        ? unwrap(await api.POST("/api/v1/users/{id}/org-assignments", { params: { path: { id } }, body }))
        : unwrap(await api.POST("/api/v1/staff/{id}/org-assignments", { params: { path: { id } }, body }));
    },
    onSuccess: (a) => { invalidatePeople(qc); toast.show("success", "Assignment added", a.orgUnitName); onOpenChange(false); },
    onError: (e) => setError(errorInfo(e).message),
  });
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Add organizational assignment"
      description="Where this person works. Assignments decide who can see and manage the person; permissions come from roles."
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={add.isPending}>Cancel</Button>
        <Button loading={add.isPending} onClick={() => { setError(null); add.mutate(); }}>Add assignment</Button>
      </>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {error && <Alert tone="danger" className="sm:col-span-2">{error}</Alert>}
        <Field label="Unit" className="sm:col-span-2">
          {(p) => <Select {...p} value={unitId} onChange={(e) => setUnitId(e.target.value)}><UnitOptions permission={permission} /></Select>}
        </Field>
        <Field label="Title (optional)" className="sm:col-span-2">{(p) => <Input {...p} maxLength={100} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
        <Field label="Starts on (optional)">{(p) => <Input {...p} type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />}</Field>
        <Field label="Ends on (optional)">{(p) => <Input {...p} type="date" value={endsOn} min={startsOn || undefined} onChange={(e) => setEndsOn(e.target.value)} />}</Field>
        <label className="flex items-center gap-3 text-sm sm:col-span-2">
          <input type="checkbox" className="h-4 w-4 accent-[var(--color-primary)]" checked={primary} onChange={(e) => setPrimary(e.target.checked)} />
          Make this the primary assignment
        </label>
      </div>
    </Modal>
  );
}

export function apiErrorCode(e: unknown): string | undefined {
  return e instanceof ApiError ? e.code : undefined;
}
