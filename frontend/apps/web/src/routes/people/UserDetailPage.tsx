import { ApiError, unwrap, type OrgAssignment, type RoleAssignment, type UserDetail } from "@ivorovi/api-client";
import { Alert, Avatar, Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, EmptyState, ErrorState, Link, Menu, MenuItem,
  PageHeader, Skeleton, TabPanel, Tabs, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, MailPlus, MoreHorizontal, Pencil, Plus, ShieldPlus, Star } from "lucide-react";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatRelative } from "../../lib/format";
import { AssignRoleDialog, invalidatePeople, OrgAssignmentDialog, RevokeRoleDialog, UserFormDialog, UserStatusDialog } from "./PeopleDialogs";
import { ACCOUNT_STATUS, INVITATION_STATE, STAFF_CATEGORIES, STATUS_ACTION_LABEL, statusActions, type AccountStatus, type InvitationState, type StaffCategory } from "./peopleModel";

type Dialog = null | "edit" | "assign-role" | "add-assignment" | { status: keyof typeof STATUS_ACTION_LABEL } | { revoke: RoleAssignment } | { remove: OrgAssignment };

/** User details: profile and invitation, organizational assignments, roles, and the effective access overview. */
export function UserDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [dialog, setDialog] = useState<Dialog>(null);
  const q = useQuery({ queryKey: ["user", id], queryFn: async () => unwrap(await api.GET("/api/v1/users/{id}", { params: { path: { id } } })) });
  const resend = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/users/{id}/invitation", { params: { path: { id } } })),
    onSuccess: (inv) => {
      invalidatePeople(qc);
      if (inv.deliveryStatus === "FAILED") toast.show("danger", "Invitation created, but the email could not be sent", "Check the mail service and send it again.");
      else toast.show("success", "Invitation sent", `A new link was emailed to ${inv.email}. Earlier links no longer work.`);
    },
    onError: (e) => toast.show("danger", "Invitation not sent", errorInfo(e).message),
  });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    if (status === 404) return <ErrorState title="User not found" message="The account may not exist or may belong to another organization." />;
    if (status === 403) return <ErrorState title="Access denied" message="This user is outside the part of the organization you can access." />;
    return <ErrorState title="Could not load this user" message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  }
  const detail = q.data;
  const user = detail.user!;
  const st = user.status as AccountStatus;
  const canManage = !!detail.canManage;
  const canAssign = !!detail.canAssignRoles;

  return (
    <>
      <Link to="/people/users" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg xl:hidden">
        <ChevronLeft aria-hidden className="h-4 w-4" /> Users
      </Link>
      <PageHeader
        title={user.displayName ?? ""}
        description={<span className="inline-flex flex-wrap items-center gap-2"><Avatar name={user.displayName ?? ""} size="sm" /><span className="break-all">{user.email}</span>
          <Badge tone={ACCOUNT_STATUS[st].tone}>{ACCOUNT_STATUS[st].label}</Badge>
          {detail.self && <Badge tone="info">You</Badge>}</span>}
        actions={canManage ? <>
          {st === "INVITED" && <Button onClick={() => resend.mutate()} loading={resend.isPending}><MailPlus aria-hidden className="h-4 w-4" /> {user.invitationStatus === "PENDING" ? "Resend invitation" : "Send invitation"}</Button>}
          <Button variant="secondary" onClick={() => setDialog("edit")}><Pencil aria-hidden className="h-4 w-4" /> Edit</Button>
          <Menu trigger={<Button variant="secondary" aria-label="Account actions"><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
            {statusActions(st).map((a) => (
              <MenuItem key={a} tone={a === "SUSPEND" || a === "DEACTIVATE" ? "danger" : undefined} onSelect={() => setDialog({ status: a })}>{STATUS_ACTION_LABEL[a]}…</MenuItem>
            ))}
          </Menu>
        </> : undefined}
      />
      {st === "SUSPENDED" || st === "DEACTIVATED" ? (
        <Alert tone="warning" className="mb-6" title={`${ACCOUNT_STATUS[st].label} ${detail.statusChangedAt ? formatRelative(detail.statusChangedAt) : ""}`}>
          {detail.statusReason ? `Reason: ${detail.statusReason}` : ACCOUNT_STATUS[st].hint}
        </Alert>
      ) : null}

      <Tabs label="User sections" value={tab} onValueChange={(v) => setParams(v === "overview" ? {} : { tab: v }, { replace: true })}
        tabs={[{ value: "overview", label: "Overview" }, { value: "assignments", label: "Organization" },
          { value: "roles", label: "Roles", count: (user.roles ?? []).length }, { value: "access", label: "Access" }]}>
        <TabPanel value="overview"><Overview detail={detail} /></TabPanel>
        <TabPanel value="assignments">
          <Assignments userId={id} canManage={canManage} onAdd={() => setDialog("add-assignment")} onRemove={(a) => setDialog({ remove: a })} />
        </TabPanel>
        <TabPanel value="roles"><Roles userId={id} canAssign={canAssign} onAssign={() => setDialog("assign-role")} onRevoke={(r) => setDialog({ revoke: r })} /></TabPanel>
        <TabPanel value="access"><AccessOverview userId={id} /></TabPanel>
      </Tabs>

      {dialog === "edit" && <UserFormDialog open onOpenChange={(o) => !o && setDialog(null)} existing={detail} />}
      {dialog === "assign-role" && <AssignRoleDialog open onOpenChange={(o) => !o && setDialog(null)} userId={id} />}
      {dialog === "add-assignment" && <OrgAssignmentDialog open onOpenChange={(o) => !o && setDialog(null)} subject="users" id={id} />}
      {dialog && typeof dialog === "object" && "status" in dialog && <UserStatusDialog open onOpenChange={(o) => !o && setDialog(null)} detail={detail} action={dialog.status} />}
      {dialog && typeof dialog === "object" && "revoke" in dialog && <RevokeRoleDialog open onOpenChange={(o) => !o && setDialog(null)} userId={id} assignment={dialog.revoke} />}
      {dialog && typeof dialog === "object" && "remove" in dialog && <RemoveAssignmentDialog subject="users" id={id} assignment={dialog.remove} onClose={() => setDialog(null)} />}
    </>
  );
}

function Overview({ detail }: { detail: UserDetail }) {
  const user = detail.user!;
  const inv = detail.invitation;
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <CardHeader title="Profile" />
        <CardBody>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
            <Item label="First name">{user.givenName}</Item>
            <Item label="Last name">{user.familyName ?? "—"}</Item>
            <Item label="Email"><span className="break-all">{user.email}</span></Item>
            <Item label="Phone">{user.phone ?? "—"}</Item>
            <Item label="Account status">{ACCOUNT_STATUS[user.status as AccountStatus].label} · <span className="font-normal text-muted">{ACCOUNT_STATUS[user.status as AccountStatus].hint}</span></Item>
            <Item label="Last sign-in">{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "Never"}</Item>
            <Item label="Created">{formatDateTime(user.createdAt)}</Item>
            <Item label="Staff profile">{detail.staffProfile
              ? <Link to={`/people/staff/${detail.staffProfile.id}`} className="text-primary hover:underline">{detail.staffProfile.staffNo} · {STAFF_CATEGORIES[detail.staffProfile.category as StaffCategory] ?? detail.staffProfile.category}</Link>
              : <span className="font-normal text-muted">Not linked</span>}</Item>
          </dl>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Invitation" />
        <CardBody>
          {!inv ? <p className="text-sm text-muted">No invitation has been sent.</p> : (
            <dl className="space-y-3 text-sm" data-testid="invitation-card">
              <Row label="Status"><Badge tone={INVITATION_STATE[inv.status as InvitationState].tone}>{INVITATION_STATE[inv.status as InvitationState].label}</Badge></Row>
              <Row label="Email delivery">{inv.deliveryStatus === "FAILED" ? <Badge tone="danger">Failed</Badge> : inv.deliveryStatus === "SENT" ? "Sent" : "Queued"}</Row>
              <Row label="Last sent">{formatRelative(inv.lastSentAt)}{(inv.sendCount ?? 1) > 1 ? ` (${inv.sendCount}×)` : ""}</Row>
              {inv.status === "PENDING" && <Row label="Expires">{formatDateTime(inv.expiresAt)}</Row>}
              {inv.acceptedAt && <Row label="Accepted">{formatDateTime(inv.acceptedAt)}</Row>}
              {inv.revokedAt && <Row label="Closed">{formatDateTime(inv.revokedAt)}</Row>}
              <Row label="Invited by">{inv.invitedByName ?? "—"}</Row>
            </dl>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function Assignments({ userId, canManage, onAdd, onRemove }: {
  userId: string; canManage: boolean; onAdd: () => void; onRemove: (a: OrgAssignment) => void;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({
    queryKey: ["user", userId, "org-assignments"],
    queryFn: async () => unwrap(await api.GET("/api/v1/users/{id}/org-assignments", { params: { path: { id: userId } } })),
  });
  const primary = useMutation({
    mutationFn: async (a: OrgAssignment) =>
      unwrap(await api.POST("/api/v1/users/{id}/org-assignments/{assignmentId}/primary", { params: { path: { id: userId, assignmentId: a.id! } } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", "Primary assignment changed"); },
    onError: (e) => toast.show("danger", "Not changed", errorInfo(e).message),
  });
  return <AssignmentTable rows={q.data} loading={q.isPending} error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
    canManage={canManage} onAdd={onAdd} onRemove={onRemove} onPrimary={(a) => primary.mutate(a)} />;
}

export function AssignmentTable({ rows, loading, error, onRetry, canManage, onAdd, onRemove, onPrimary }: {
  rows: OrgAssignment[] | undefined; loading: boolean; error: { message: string } | null; onRetry: () => void;
  canManage: boolean; onAdd: () => void; onRemove: (a: OrgAssignment) => void; onPrimary: (a: OrgAssignment) => void;
}) {
  const columns: Column<OrgAssignment>[] = [
    { key: "unit", header: "Unit", cell: (a) => (
      <span className="flex flex-wrap items-center gap-2">
        {a.orgUnitId ? <Link to={`/organization/units/${a.orgUnitId}`} className="font-medium text-primary hover:underline">{a.orgUnitName}</Link> : <span className="font-medium">{a.orgUnitName}</span>}
        {a.primary && <Badge tone="info">Primary</Badge>}
        {a.source === "STAFF" && <Badge>Via staff profile</Badge>}
      </span>
    ) },
    { key: "title", header: "Title", hideOnMobile: true, cell: (a) => a.title ?? "—" },
    { key: "period", header: "Period", hideOnMobile: true, cell: (a) => a.startsOn || a.endsOn ? `${a.startsOn ?? "…"} – ${a.endsOn ?? "open"}` : "Open-ended" },
    ...(canManage ? [{ key: "actions", header: "Actions", cell: (a: OrgAssignment) => a.source === "STAFF" && rows?.some((r) => r.source === "USER") ? null : (
      <Menu trigger={<Button variant="ghost" size="sm" aria-label={`Actions for ${a.orgUnitName}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
        {!a.primary && <MenuItem onSelect={() => onPrimary(a)}><Star aria-hidden className="h-4 w-4" /> Make primary</MenuItem>}
        <MenuItem tone="danger" onSelect={() => onRemove(a)}>Remove…</MenuItem>
      </Menu>
    ) }] : []),
  ];
  return (
    <Card>
      <CardHeader title="Organizational assignments" description="Where this person belongs. Assignments decide who can see and manage them; permissions come from roles."
        actions={canManage ? <Button size="sm" variant="secondary" onClick={onAdd}><Plus aria-hidden className="h-4 w-4" /> Add</Button> : undefined} />
      <DataTable caption="Organizational assignments" columns={columns} rows={rows} rowKey={(a) => a.id!} loading={loading} error={error} onRetry={onRetry}
        empty={{ title: "No assignments" }} />
    </Card>
  );
}

export function RemoveAssignmentDialog({ subject, id, assignment, onClose }: { subject: "users" | "staff"; id: string; assignment: OrgAssignment; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const remove = useMutation({
    mutationFn: async () => subject === "users"
      ? unwrap(await api.DELETE("/api/v1/users/{id}/org-assignments/{assignmentId}", { params: { path: { id, assignmentId: assignment.id! } } }))
      : unwrap(await api.DELETE("/api/v1/staff/{id}/org-assignments/{assignmentId}", { params: { path: { id, assignmentId: assignment.id! } } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", "Assignment removed", assignment.orgUnitName); onClose(); },
    onError: (e) => { toast.show("danger", "Not removed", errorInfo(e).message); onClose(); },
  });
  return <ConfirmDialog open onOpenChange={(o) => !o && onClose()} title={`Remove assignment to ${assignment.orgUnitName}?`} tone="danger" confirmLabel="Remove"
    busy={remove.isPending} onConfirm={() => remove.mutate()}
    description={<p>Administrators of that unit may no longer see this person. Roles are not changed.</p>} />;
}

function Roles({ userId, canAssign, onAssign, onRevoke }: { userId: string; canAssign: boolean; onAssign: () => void; onRevoke: (r: RoleAssignment) => void }) {
  const api = useApi();
  const q = useQuery({ queryKey: ["user", userId, "roles"], queryFn: async () => unwrap(await api.GET("/api/v1/users/{id}/role-assignments", { params: { path: { id: userId } } })) });
  const columns: Column<RoleAssignment>[] = [
    { key: "role", header: "Role", cell: (r) => <span className="font-medium">{r.roleName}</span> },
    { key: "scope", header: "Scope", cell: (r) => r.orgUnitId ? <Link to={`/organization/units/${r.orgUnitId}`} className="text-primary hover:underline">{r.scopeName}</Link> : r.scopeName },
    { key: "status", header: "Status", cell: (r) => r.active ? <Badge tone="success">Active</Badge> : r.revokedAt ? <Badge>Revoked</Badge> : <Badge tone="warning">Expired</Badge> },
    { key: "period", header: "Valid", hideOnMobile: true, cell: (r) => <span className="text-xs">{formatDateTime(r.validFrom)}{r.validTo ? ` – ${formatDateTime(r.validTo)}` : ""}</span> },
    { key: "by", header: "Granted / revoked by", hideOnMobile: true, cell: (r) => (
      <span className="text-xs">{r.grantedByName ?? "System"}{r.revokedAt && <span className="block text-muted">Revoked by {r.revokedByName ?? "—"}: {r.revocationReason}</span>}</span>
    ) },
    ...(canAssign ? [{ key: "actions", header: "Actions", cell: (r: RoleAssignment) => r.active
      ? <Button variant="ghost" size="sm" onClick={() => onRevoke(r)} aria-label={`Revoke ${r.roleName} at ${r.scopeName}`}>Revoke</Button> : null }] : []),
  ];
  return (
    <Card>
      <CardHeader title="Role assignments" description="Roles give permissions at a scope (the organization or a unit and everything below it). Custom roles arrive in increment 1.3."
        actions={canAssign ? <Button size="sm" onClick={onAssign}><ShieldPlus aria-hidden className="h-4 w-4" /> Assign role</Button> : undefined} />
      <DataTable caption="Role assignments" columns={columns} rows={q.data} rowKey={(r) => r.id!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        empty={{ title: "No roles assigned", description: "Without a role the user can sign in but sees only their own account." }} />
    </Card>
  );
}

function AccessOverview({ userId }: { userId: string }) {
  const api = useApi();
  const q = useQuery({ queryKey: ["user", userId, "access"], queryFn: async () => unwrap(await api.GET("/api/v1/users/{id}/access", { params: { path: { id: userId } } })) });
  if (q.isPending) return <Card><CardBody className="space-y-3">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-8 w-full" />)}</CardBody></Card>;
  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  const a = q.data;
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <Card>
        <CardHeader title="Summary" />
        <CardBody>
          <dl className="space-y-3 text-sm">
            <Row label="Account">{ACCOUNT_STATUS[a.status as AccountStatus].label}</Row>
            <Row label="Invitation">{INVITATION_STATE[a.invitationStatus as InvitationState].label}</Row>
            <Row label="Active roles">{(a.roles ?? []).length}</Row>
            <Row label="Permissions">{(a.permissions ?? []).length}</Row>
          </dl>
          <p className="mt-4 text-xs font-medium uppercase tracking-wider text-muted">Assignments</p>
          <ul className="mt-2 space-y-1 text-sm">{(a.assignments ?? []).map((x) => <li key={`${x.source}:${x.id}`}>{x.orgUnitName}{x.primary ? " (primary)" : ""}{x.source === "STAFF" && <span className="text-muted"> · via staff profile</span>}</li>)}</ul>
          {a.status !== "ACTIVE" && <Alert tone="warning" className="mt-4">The account is not active, so none of these permissions can be used right now.</Alert>}
        </CardBody>
      </Card>
      <Card className="xl:col-span-2">
        <CardHeader title="Effective permissions" description="What the user can do and where. A permission on a unit also covers everything below it." />
        {(a.permissions ?? []).length === 0 ? <EmptyState title="No permissions" description="Assign a role to give access." /> : (
          <ul className="divide-y divide-border" data-testid="effective-permissions">
            {a.permissions!.map((p) => (
              <li key={p.permission} className="px-5 py-3 sm:px-6">
                <p className="text-sm font-medium text-fg">{p.description}</p>
                <p className="mt-0.5 font-mono text-xs text-muted">{p.permission}</p>
                <p className="mt-2 flex flex-wrap gap-1.5">
                  {(p.scopes ?? []).map((s) => <Badge key={`${s.scopeType}:${s.orgUnitId}`} tone={s.scopeType === "TENANT" ? "info" : "neutral"}>{s.scopeName} · via {(s.viaRoles ?? []).join(", ")}</Badge>)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-muted">{label}</dt><dd className="mt-1 font-medium text-fg">{children}</dd></div>;
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="flex items-center justify-between gap-3"><dt className="text-muted">{label}</dt><dd className="text-right font-medium text-fg">{children}</dd></div>;
}

export default UserDetailPage;
