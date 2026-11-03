import { unwrap, type Invitation } from "@ivorovi/api-client";
import { Badge, Button, Card, ConfirmDialog, DataTable, Field, FilterBar, Input, Link, Menu, MenuItem, PageHeader, Select, useToast, type Column } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MoreHorizontal, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { invalidatePeople } from "./PeopleDialogs";
import { INVITATION_STATE, type InvitationState } from "./peopleModel";

const PAGE_SIZE = 20;
type State = Exclude<InvitationState, "NONE">;

/** Invitation management: status of every invitation, re-send and revoke. */
export function InvitationsPage() {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const status = params.get("status") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const [search, setSearch] = useState(q);
  const [revoking, setRevoking] = useState<Invitation | null>(null);

  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);
  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v ? n.set(k, v) : n.delete(k))); return n; });

  const query = useQuery({
    queryKey: ["invitations", { q, status, page }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/invitations", { params: { query: { page, size: PAGE_SIZE, ...(q ? { q } : {}), ...(status ? { status: status as State } : {}) } } })),
  });
  const resend = useMutation({
    mutationFn: async (inv: Invitation) => unwrap(await api.POST("/api/v1/users/{id}/invitation", { params: { path: { id: inv.userId! } } })),
    onSuccess: (inv) => { invalidatePeople(qc); toast.show(inv.deliveryStatus === "FAILED" ? "danger" : "success", inv.deliveryStatus === "FAILED" ? "Email could not be sent" : "Invitation sent", inv.email); },
    onError: (e) => toast.show("danger", "Invitation not sent", errorInfo(e).message),
  });
  const revoke = useMutation({
    mutationFn: async (inv: Invitation) => unwrap(await api.POST("/api/v1/invitations/{id}/revoke", { params: { path: { id: inv.id! } } })),
    onSuccess: (_, inv) => { invalidatePeople(qc); toast.show("success", "Invitation revoked", `The link sent to ${inv.email} no longer works.`); setRevoking(null); },
    onError: (e) => { toast.show("danger", "Not revoked", errorInfo(e).message); setRevoking(null); },
  });

  const canManage = access.hasAnywhere("user.manage");
  const columns: Column<Invitation>[] = [
    { key: "user", header: "Invitee", cell: (i) => (
      <span className="block min-w-0">
        <Link to={`/people/users/${i.userId}`} className="block truncate font-medium text-primary hover:underline">{i.userName}</Link>
        <span className="block truncate text-xs text-muted">{i.email}</span>
      </span>
    ) },
    { key: "status", header: "Status", cell: (i) => (
      <span className="flex flex-col items-start gap-1">
        <Badge tone={INVITATION_STATE[i.status as State].tone}>{INVITATION_STATE[i.status as State].label}</Badge>
        {i.status === "PENDING" && i.deliveryStatus === "FAILED" && <Badge tone="danger">Email failed</Badge>}
      </span>
    ) },
    { key: "sent", header: "Last sent", hideOnMobile: true, cell: (i) => <span>{formatRelative(i.lastSentAt)}{(i.sendCount ?? 1) > 1 && <span className="text-muted"> · {i.sendCount}×</span>}</span> },
    { key: "expires", header: "Expires", hideOnMobile: true, cell: (i) => i.status === "PENDING" ? formatDateTime(i.expiresAt) : "—" },
    { key: "by", header: "Invited by", hideOnMobile: true, cell: (i) => i.invitedByName ?? "—" },
    ...(canManage ? [{ key: "actions", header: "Actions", cell: (i: Invitation) => (i.status === "PENDING" || i.status === "EXPIRED") ? (
      <Menu trigger={<Button variant="ghost" size="sm" aria-label={`Actions for ${i.email}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
        <MenuItem onSelect={() => resend.mutate(i)}>{i.status === "PENDING" ? "Resend" : "Send new invitation"}</MenuItem>
        {i.status === "PENDING" && <MenuItem tone="danger" onSelect={() => setRevoking(i)}>Revoke…</MenuItem>}
      </Menu>
    ) : null }] : []),
  ];

  return (
    <>
      <PageHeader title="Invitations" description="Invitation links are single-use and expire after 7 days. Re-sending replaces the previous link." />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search invitations</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by name or email" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Status" className="w-full sm:w-40">
            {(p) => (
              <Select {...p} value={status} onChange={(e) => update({ status: e.target.value, page: null })}>
                <option value="">All</option>
                {(["PENDING", "EXPIRED", "ACCEPTED", "REVOKED"] as State[]).map((s) => <option key={s} value={s}>{INVITATION_STATE[s].label}</option>)}
              </Select>
            )}
          </Field>
        </FilterBar>
        <DataTable caption="Invitations" columns={columns} rows={query.data?.items} rowKey={(i) => i.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{ title: q || status ? "No invitations match these filters" : "No invitations yet", description: q || status ? "Clear a filter or try a different search." : "Invitations appear here when you create users." }} />
      </Card>
      {revoking && (
        <ConfirmDialog open onOpenChange={(o) => !o && setRevoking(null)} title={`Revoke the invitation for ${revoking.email}?`} tone="danger" confirmLabel="Revoke"
          busy={revoke.isPending} onConfirm={() => revoke.mutate(revoking)}
          description={<p>The link stops working immediately. You can send a new invitation later; the account stays invited until then.</p>} />
      )}
    </>
  );
}

export default InvitationsPage;
