import { ApiError, unwrap, type RoleDetail, type RoleHolder } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Link, Menu, MenuItem,
  PageHeader, Skeleton, TabPanel, Tabs, TextArea, useToast, type Column } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, Copy, MoreHorizontal, Pencil } from "lucide-react";
import { useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { effectiveAccess, LEVEL } from "./accessModel";

type Dialog = null | "deactivate" | "activate" | "archive";

export function RoleDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "permissions";
  const [dialog, setDialog] = useState<Dialog>(null);
  const q = useQuery({ queryKey: ["role", id], queryFn: async () => unwrap(await api.GET("/api/v1/roles/{id}", { params: { path: { id } } })) });
  const catalogue = useQuery({ queryKey: ["permissions"], staleTime: 5 * 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/permissions")) });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    return <ErrorState title={status === 404 ? "Role not found" : "Could not load this role"}
      message={status === 404 ? "It may have been archived or belong to another organization." : errorInfo(q.error).message}
      {...(status === 404 ? {} : { onRetry: () => void q.refetch() })} />;
  }
  const detail = q.data;
  const role = detail.role!;
  const summary = effectiveAccess(catalogue.data, detail.permissions ?? []);

  return (
    <>
      <Link to="/access/roles" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg xl:hidden"><ChevronLeft aria-hidden className="h-4 w-4" /> Roles</Link>
      <PageHeader title={role.name ?? ""}
        description={<span className="inline-flex flex-wrap items-center gap-2">
          <Badge tone={role.system ? "info" : "neutral"}>{role.system ? "System role" : "Custom role"}</Badge>
          {role.status === "ACTIVE" ? <Badge tone="success">Active</Badge> : <Badge tone="warning">Inactive</Badge>}
          <code className="text-xs">{role.code}</code>
        </span>}
        actions={<>
          {access.hasAnywhere("role.manage") && <ButtonLink to={`/access/roles/new?from=${role.id}`}><Copy aria-hidden className="h-4 w-4" /> Clone</ButtonLink>}
          {detail.canEdit && <>
            <ButtonLink to={`/access/roles/${role.id}/edit`} variant="primary"><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>
            <Menu trigger={<Button variant="secondary" aria-label="Role actions"><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
              {role.status === "ACTIVE"
                ? <MenuItem tone="danger" onSelect={() => setDialog("deactivate")}>Deactivate…</MenuItem>
                : <MenuItem onSelect={() => setDialog("activate")}>Activate…</MenuItem>}
              <MenuItem tone="danger" onSelect={() => setDialog("archive")}>Archive…</MenuItem>
            </Menu>
          </>}
        </>} />
      {role.system && <Alert tone="info" className="mb-6">System roles are defined by Ivorovi and cannot be changed. Clone this role to create an editable custom role.</Alert>}
      {role.status === "INACTIVE" && (
        <Alert tone="warning" className="mb-6" title="This role is deactivated">
          Its {role.activeAssignments} assignment(s) grant nothing until the role is activated again.{detail.statusReason ? ` Reason: ${detail.statusReason}` : ""}
        </Alert>
      )}

      <Tabs label="Role sections" value={tab} onValueChange={(v) => setParams(v === "permissions" ? {} : { tab: v }, { replace: true })}
        tabs={[{ value: "permissions", label: "Permissions", count: detail.permissions?.length ?? 0 },
          { value: "holders", label: "Assigned users", count: role.activeAssignments ?? 0 }, { value: "overview", label: "Overview" }]}>
        <TabPanel value="permissions">
          <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <CardHeader title="Permissions in this role" description="Grouped by area." />
              {summary.domains.length === 0 ? <CardBody><p className="text-sm text-muted">No permissions.</p></CardBody> : (
                <div className="divide-y divide-border" data-testid="role-permissions">
                  {summary.domains.map((d) => (
                    <div key={d.label} className="px-5 py-4 sm:px-6">
                      <h3 className="text-sm font-semibold text-fg">{d.label}</h3>
                      <ul className="mt-2 space-y-2">
                        {d.permissions.map((p) => (
                          <li key={p.code} className="flex flex-wrap items-center gap-2 text-sm">
                            <span className="font-medium text-fg">{p.name}</span>
                            <Badge tone={LEVEL[p.level ?? "READ"]!.tone}>{LEVEL[p.level ?? "READ"]!.label}</Badge>
                            {p.scope === "ORGANIZATION" && <Badge>Organization-wide</Badge>}
                            <code className="text-xs text-muted-strong">{p.code}</code>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              )}
            </Card>
            <Card>
              <CardHeader title="Effective access" />
              <CardBody className="space-y-3 text-sm">
                <p>{role.scopeOrgUnitId
                  ? <>Usable only within <strong>{role.scopeName}</strong> and the units below it.</>
                  : role.organizationOnly && !role.system ? "Assignable only for the whole organization."
                  : "Assignable for the whole organization or for any unit; a unit assignment covers everything below that unit."}</p>
                {summary.warnings.map((w) => <p key={w} className="rounded-lg bg-warning-soft p-2.5 text-xs text-warning-text">{w}</p>)}
                {(detail.beyondYourCeiling ?? []).length > 0 && !role.system && (
                  <p className="text-xs text-muted-strong">You do not hold {detail.beyondYourCeiling!.length} of these permissions yourself, so you could not assign this role or add them.</p>
                )}
              </CardBody>
            </Card>
          </div>
        </TabPanel>
        <TabPanel value="holders"><Holders roleId={role.id!} /></TabPanel>
        <TabPanel value="overview"><Overview detail={detail} /></TabPanel>
      </Tabs>
      {(dialog === "deactivate" || dialog === "activate") && <StatusDialog detail={detail} target={dialog === "deactivate" ? "INACTIVE" : "ACTIVE"} onClose={() => setDialog(null)} />}
      {dialog === "archive" && <ArchiveDialog detail={detail} onClose={() => setDialog(null)} />}
    </>
  );
}

function Overview({ detail }: { detail: RoleDetail }) {
  const role = detail.role!;
  const scopes = Object.entries((detail.assignmentsByScope ?? {}) as Record<string, number>);
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <CardHeader title="About this role" />
        <CardBody>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
            <Item label="Name">{role.name}</Item>
            <Item label="Code"><code>{role.code}</code></Item>
            <Item label="Description">{role.description || "—"}</Item>
            <Item label="Type">{role.system ? "System (defined by Ivorovi)" : "Custom (defined by your organization)"}</Item>
            <Item label="Usable in">{role.scopeName}</Item>
            <Item label="Cloned from">{detail.clonedFromName ?? "—"}</Item>
            <Item label="Last changed">{role.system ? "—" : formatDateTime(role.updatedAt)}</Item>
          </dl>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Assignment scope" description="Where the current assignments apply" />
        <CardBody>
          {scopes.length === 0 ? <p className="text-sm text-muted">Not assigned to anyone.</p> : (
            <dl className="space-y-2 text-sm" data-testid="assignment-scopes">
              {scopes.map(([k, n]) => <div key={k} className="flex justify-between gap-3"><dt className="text-muted">{k === "Whole organization" ? k : k.charAt(0) + k.slice(1).toLowerCase()}</dt><dd className="tabular font-semibold">{n}</dd></div>)}
            </dl>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function Holders({ roleId }: { roleId: string }) {
  const api = useApi();
  const [page, setPage] = useState(0);
  const [inactive, setInactive] = useState(false);
  const q = useQuery({ queryKey: ["role", roleId, "holders", page, inactive], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/roles/{id}/holders", { params: { path: { id: roleId }, query: { page, size: 20, includeInactive: inactive } } })) });
  const columns: Column<RoleHolder>[] = [
    { key: "user", header: "User", cell: (h) => <span className="block min-w-0"><Link to={`/people/users/${h.userId}`} className="block truncate font-medium text-primary hover:underline">{h.userName}</Link><span className="block truncate text-xs text-muted">{h.email}</span></span> },
    { key: "scope", header: "Scope", cell: (h) => h.orgUnitId ? <Link to={`/organization/units/${h.orgUnitId}`} className="hover:underline">{h.scopeName}</Link> : h.scopeName },
    { key: "valid", header: "Valid", hideOnMobile: true, cell: (h) => <span className="text-xs">{formatDateTime(h.validFrom)}{h.validTo ? ` – ${formatDateTime(h.validTo)}` : ""}</span> },
    { key: "status", header: "Status", hideOnMobile: true, cell: (h) => h.active ? <Badge tone="success">Current</Badge> : <Badge>Ended</Badge> },
  ];
  return (
    <Card>
      <CardHeader title="Assigned users" description="Only users you are allowed to see are listed. Manage assignments on each user's Roles tab."
        actions={<label className="flex items-center gap-2 text-sm"><input type="checkbox" className="h-4 w-4 accent-[var(--color-primary)]" checked={inactive} onChange={(e) => { setInactive(e.target.checked); setPage(0); }} /> Include ended</label>} />
      <DataTable caption="Assigned users" columns={columns} rows={q.data?.items} rowKey={(h) => h.assignmentId!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        page={q.data ? { page, totalPages: q.data.totalPages ?? 0, totalElements: q.data.totalElements ?? 0, onPageChange: setPage } : undefined}
        empty={{ title: "Nobody holds this role", description: "Assign it from a user's Roles tab." }} />
    </Card>
  );
}

function StatusDialog({ detail, target, onClose }: { detail: RoleDetail; target: "ACTIVE" | "INACTIVE"; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const role = detail.role!;
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const change = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/roles/{id}/status", { params: { path: { id: role.id! } },
      body: { status: target, version: role.version!, ...(reason.trim() ? { reason: reason.trim() } : {}) } })),
    onSuccess: () => {
      for (const k of ["roles", "role", "users", "user"]) void qc.invalidateQueries({ queryKey: [k] });
      toast.show("success", target === "INACTIVE" ? "Role deactivated" : "Role activated", role.name); onClose();
    },
    onError: (e) => setError(errorInfo(e).message),
  });
  const deactivating = target === "INACTIVE";
  return (
    <ConfirmDialog open onOpenChange={(o) => !o && onClose()} title={`${deactivating ? "Deactivate" : "Activate"} ${role.name}?`}
      tone={deactivating ? "danger" : "primary"} confirmLabel={deactivating ? "Deactivate" : "Activate"} busy={change.isPending}
      onConfirm={() => { if (deactivating && !reason.trim()) { setError("Give a reason; it is recorded in the audit log."); return; } setError(null); change.mutate(); }}
      description={deactivating
        ? <p><strong>{role.activeAssignments} assignment(s)</strong> stop granting access immediately; the people keep their other roles. The assignments are kept, so activating the role again restores them. New assignments of this role are not possible while it is inactive.</p>
        : <p>The role's {role.activeAssignments} assignment(s) grant access again immediately.</p>}>
      <div className="mt-4 space-y-3">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={deactivating ? "Reason (required)" : "Reason (optional)"}>{(p) => <TextArea {...p} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      </div>
    </ConfirmDialog>
  );
}

function ArchiveDialog({ detail, onClose }: { detail: RoleDetail; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const role = detail.role!;
  const inUse = (role.activeAssignments ?? 0) > 0;
  const archive = useMutation({
    mutationFn: async () => unwrap(await api.DELETE("/api/v1/roles/{id}", { params: { path: { id: role.id! }, query: { version: role.version! } } })),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["roles"] }); toast.show("success", "Role archived", role.name); onClose(); void navigate("/access/roles", { replace: true }); },
    onError: (e) => { toast.show("danger", "Not archived", errorInfo(e).message); onClose(); },
  });
  return (
    <ConfirmDialog open onOpenChange={(o) => !o && onClose()} title={`Archive ${role.name}?`} tone="danger" confirmLabel="Archive" busy={archive.isPending}
      onConfirm={() => (inUse ? onClose() : archive.mutate())}
      description={inUse
        ? <p>This role is still assigned <strong>{role.activeAssignments}</strong> time(s). Revoke or reassign those assignments first, or deactivate the role instead.</p>
        : <p>The role disappears from lists and can no longer be assigned. Past assignments stay in the users' history and the audit log.</p>} />
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-muted">{label}</dt><dd className="mt-1 font-medium text-fg">{children}</dd></div>;
}

export default RoleDetailPage;
