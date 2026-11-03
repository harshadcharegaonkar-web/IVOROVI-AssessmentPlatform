import { unwrap, type UserSummary } from "@ivorovi/api-client";
import { Avatar, Badge, Button, Card, DataTable, Field, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search, UserPlus } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions, UserFormDialog } from "./PeopleDialogs";
import { ACCOUNT_STATUS, INVITATION_STATE, type AccountStatus, type InvitationState } from "./peopleModel";

const PAGE_SIZE = 20;
const SORTABLE = ["name", "email", "status", "lastLoginAt", "createdAt"];

/** Users of the organization visible to the caller: search, status/invitation/unit/role filters, sort, pages. */
export function UserListPage() {
  const api = useApi();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const q = params.get("q") ?? "";
  const status = params.get("status") ?? "";
  const invitation = params.get("invitation") ?? "";
  const unit = params.get("unit") ?? "";
  const role = params.get("role") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const sortKey = SORTABLE.includes(params.get("sort") ?? "") ? params.get("sort")! : "name";
  const direction = params.get("dir") === "desc" ? "desc" : "asc";
  const [search, setSearch] = useState(q);

  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);

  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v ? n.set(k, v) : n.delete(k))); return n; });

  const roles = useQuery({ queryKey: ["roles"], enabled: access.hasAnywhere("role.read"), staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/roles")) });
  const query = useQuery({
    queryKey: ["users", { q, status, invitation, unit, role, page, sortKey, direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/users", {
      params: { query: { page, size: PAGE_SIZE, sort: sortKey as "name", direction, ...(q ? { q } : {}),
        ...(status ? { status: status as AccountStatus } : {}), ...(invitation ? { invitation: invitation as InvitationState } : {}),
        ...(unit ? { orgUnitId: unit } : {}), ...(role ? { role } : {}) } },
    })),
  });

  const columns: Column<UserSummary>[] = [
    { key: "name", header: "Name", sortable: true, cell: (u) => (
      <span className="flex min-w-0 items-center gap-3">
        <Avatar name={u.displayName ?? ""} size="sm" />
        <span className="min-w-0">
          <Link to={`/people/users/${u.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{u.displayName}</Link>
          <span className="block truncate text-xs text-muted">{u.email}</span>
          <span className="mt-1 block md:hidden"><Badge tone={ACCOUNT_STATUS[u.status as AccountStatus].tone}>{ACCOUNT_STATUS[u.status as AccountStatus].label}</Badge></span>
        </span>
      </span>
    ) },
    { key: "units", header: "Assignments", hideOnMobile: true, cell: (u) => <span className="line-clamp-2 text-sm">{(u.units ?? []).join(", ") || "—"}</span> },
    { key: "roles", header: "Roles", hideOnMobile: true, cell: (u) => (u.roles ?? []).length === 0 ? <span className="text-muted">None</span>
      : <span className="flex flex-wrap gap-1">{u.roles!.slice(0, 2).map((r) => <Badge key={r}>{roleName(roles.data, r)}</Badge>)}{u.roles!.length > 2 && <Badge>+{u.roles!.length - 2}</Badge>}</span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (u) => (
      <span className="flex flex-col items-start gap-1">
        <Badge tone={ACCOUNT_STATUS[u.status as AccountStatus].tone}>{ACCOUNT_STATUS[u.status as AccountStatus].label}</Badge>
        {u.status === "INVITED" && u.invitationStatus !== "PENDING" && <Badge tone={INVITATION_STATE[u.invitationStatus as InvitationState].tone}>Invitation {INVITATION_STATE[u.invitationStatus as InvitationState].label.toLowerCase()}</Badge>}
      </span>
    ) },
    { key: "lastLoginAt", header: "Last sign-in", sortable: true, hideOnMobile: true, cell: (u) => u.lastLoginAt ? formatRelative(u.lastLoginAt) : <span className="text-muted">Never</span> },
  ];
  const filtered = !!(q || status || invitation || unit || role);

  return (
    <>
      <PageHeader title="Users" description="Accounts that can sign in to your organization, and invited people who have not signed in yet."
        actions={access.hasAnywhere("user.manage") ? <Button onClick={() => setCreating(true)}><UserPlus aria-hidden className="h-4 w-4" /> New user</Button> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search users</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by name or email" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Status" className="w-full sm:w-36">
            {(p) => (
              <Select {...p} value={status} onChange={(e) => update({ status: e.target.value, page: null })}>
                <option value="">All</option>
                {(Object.keys(ACCOUNT_STATUS) as AccountStatus[]).map((s) => <option key={s} value={s}>{ACCOUNT_STATUS[s].label}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Invitation" className="w-full sm:w-36">
            {(p) => (
              <Select {...p} value={invitation} onChange={(e) => update({ invitation: e.target.value, page: null })}>
                <option value="">Any</option>
                {(Object.keys(INVITATION_STATE) as InvitationState[]).map((s) => <option key={s} value={s}>{INVITATION_STATE[s].label}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Unit" className="w-full sm:w-56">
            {(p) => <Select {...p} value={unit} onChange={(e) => update({ unit: e.target.value, page: null })}><option value="">Any</option><UnitOptions permission="user.read" allowOrganization={false} /></Select>}
          </Field>
          {(roles.data ?? []).length > 0 && (
            <Field label="Role" className="w-full sm:w-48">
              {(p) => (
                <Select {...p} value={role} onChange={(e) => update({ role: e.target.value, page: null })}>
                  <option value="">Any</option>
                  {roles.data!.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
                </Select>
              )}
            </Field>
          )}
        </FilterBar>
        <DataTable caption="Users" columns={columns} rows={query.data?.items} rowKey={(u) => u.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          sort={{ key: sortKey, direction }} onSortChange={(s) => update({ sort: s.key, dir: s.direction, page: null })}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{
            title: filtered ? "No users match these filters" : "No users yet",
            description: filtered ? "Clear a filter or try a different search." : "Invite the people who should sign in.",
            action: !filtered && access.hasAnywhere("user.manage") ? <Button onClick={() => setCreating(true)}><UserPlus aria-hidden className="h-4 w-4" /> New user</Button> : undefined,
          }} />
      </Card>
      {creating && <UserFormDialog open onOpenChange={setCreating} defaultUnitId={unit || undefined} />}
    </>
  );
}

function roleName(roles: { code?: string; name?: string }[] | undefined, code: string): string {
  return roles?.find((r) => r.code === code)?.name ?? code.replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export default UserListPage;
