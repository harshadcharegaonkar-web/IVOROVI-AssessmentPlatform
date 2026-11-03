import { unwrap, type StaffProfile } from "@ivorovi/api-client";
import { Badge, Button, Card, DataTable, Field, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "./PeopleDialogs";
import { StaffFormDialog } from "./StaffDialogs";
import { ACCOUNT_STATUS, EMPLOYMENT_STATUS, STAFF_CATEGORIES, type AccountStatus, type EmploymentStatus, type StaffCategory } from "./peopleModel";

const PAGE_SIZE = 20;
const SORTABLE = ["name", "staffNo", "category", "status", "joinedOn"];

/** Staff directory: employment records, with or without a login. */
export function StaffListPage() {
  const api = useApi();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const q = params.get("q") ?? "";
  const category = params.get("category") ?? "";
  const status = params.get("status") ?? "";
  const unit = params.get("unit") ?? "";
  const linked = params.get("linked") ?? "";
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

  const query = useQuery({
    queryKey: ["staff", { q, category, status, unit, linked, page, sortKey, direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/staff", {
      params: { query: { page, size: PAGE_SIZE, sort: sortKey as "name", direction, ...(q ? { q } : {}),
        ...(category ? { category: category as StaffCategory } : {}), ...(status ? { status: status as EmploymentStatus } : {}),
        ...(unit ? { orgUnitId: unit } : {}), ...(linked ? { linked: linked === "yes" } : {}) } },
    })),
  });

  const columns: Column<StaffProfile>[] = [
    { key: "name", header: "Name", sortable: true, cell: (s) => (
      <span className="block min-w-0">
        <Link to={`/people/staff/${s.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{s.displayName}</Link>
        <span className="block truncate text-xs text-muted">{s.designation ?? STAFF_CATEGORIES[s.category as StaffCategory]}</span>
      </span>
    ) },
    { key: "staffNo", header: "Staff ID", sortable: true, cell: (s) => <code className="text-xs">{s.staffNo}</code> },
    { key: "category", header: "Category", sortable: true, hideOnMobile: true, cell: (s) => STAFF_CATEGORIES[s.category as StaffCategory] },
    { key: "unit", header: "Primary unit", hideOnMobile: true, cell: (s) => s.primaryUnitName ?? "—" },
    { key: "status", header: "Employment", sortable: true, cell: (s) => <Badge tone={EMPLOYMENT_STATUS[s.employmentStatus as EmploymentStatus].tone}>{EMPLOYMENT_STATUS[s.employmentStatus as EmploymentStatus].label}</Badge> },
    { key: "login", header: "Login", hideOnMobile: true, cell: (s) => s.userId
      ? <Link to={`/people/users/${s.userId}`} className="hover:underline"><Badge tone={ACCOUNT_STATUS[s.userStatus as AccountStatus].tone}>{ACCOUNT_STATUS[s.userStatus as AccountStatus].label}</Badge></Link>
      : <span className="text-muted">No login</span> },
  ];
  const filtered = !!(q || category || status || unit || linked);

  return (
    <>
      <PageHeader title="Staff" description="Faculty and staff of your organization. A profile does not need a login."
        actions={access.hasAnywhere("staff.manage") ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New staff profile</Button> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search staff</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Name, staff ID, email or designation" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Category" className="w-full sm:w-52">
            {(p) => (
              <Select {...p} value={category} onChange={(e) => update({ category: e.target.value, page: null })}>
                <option value="">All</option>
                {(Object.keys(STAFF_CATEGORIES) as StaffCategory[]).map((c) => <option key={c} value={c}>{STAFF_CATEGORIES[c]}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Employment" className="w-full sm:w-36">
            {(p) => (
              <Select {...p} value={status} onChange={(e) => update({ status: e.target.value, page: null })}>
                <option value="">All</option>
                {(Object.keys(EMPLOYMENT_STATUS) as EmploymentStatus[]).map((s) => <option key={s} value={s}>{EMPLOYMENT_STATUS[s].label}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Unit" className="w-full sm:w-56">
            {(p) => <Select {...p} value={unit} onChange={(e) => update({ unit: e.target.value, page: null })}><option value="">Any</option><UnitOptions permission="staff.read" allowOrganization={false} /></Select>}
          </Field>
          <Field label="Login" className="w-full sm:w-36">
            {(p) => (
              <Select {...p} value={linked} onChange={(e) => update({ linked: e.target.value, page: null })}>
                <option value="">Any</option><option value="yes">Has login</option><option value="no">No login</option>
              </Select>
            )}
          </Field>
        </FilterBar>
        <DataTable caption="Staff" columns={columns} rows={query.data?.items} rowKey={(s) => s.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          sort={{ key: sortKey, direction }} onSortChange={(s) => update({ sort: s.key, dir: s.direction, page: null })}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{
            title: filtered ? "No staff match these filters" : "No staff profiles yet",
            description: filtered ? "Clear a filter or try a different search." : "Add faculty and staff; they can be given logins later.",
            action: !filtered && access.hasAnywhere("staff.manage") ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New staff profile</Button> : undefined,
          }} />
      </Card>
      {creating && <StaffFormDialog open onOpenChange={setCreating} />}
    </>
  );
}

export default StaffListPage;
