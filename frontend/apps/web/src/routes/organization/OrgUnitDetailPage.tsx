import { ApiError, unwrap, type OrgUnit, type OrgUnitHistoryEntry, type OrgUnitType } from "@ivorovi/api-client";
import { Badge, Breadcrumbs, Button, Card, CardBody, CardHeader, DataTable, ErrorState, Link, Menu, MenuItem, PageHeader, Skeleton, TabPanel, Tabs, Tooltip, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ArrowRightLeft, ChevronLeft, MoreHorizontal, Pencil, Plus, Power, Trash2 } from "lucide-react";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { FeatureConfigurationPanel } from "../features/FeatureConfigurationPanel";
import { OrgStatusBadge } from "./OrgStatusBadge";
import { DeleteDialog, MoveDialog, OrgUnitFormDialog, StatusDialog } from "./OrgUnitDialogs";
import { ALLOWED_PARENTS, isInstitution, TYPE_ROUTES, UNIT_TYPES } from "./orgModel";

type DialogKind = "edit" | "move" | "status" | "delete" | { create: OrgUnitType } | null;

export function OrgUnitDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [dialog, setDialog] = useState<DialogKind>(null);
  const q = useQuery({ queryKey: ["org-unit", id], queryFn: async () => unwrap(await api.GET("/api/v1/org-units/{id}", { params: { path: { id } } })) });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    if (status === 404) return <ErrorState title="Unit not found" message="It may not exist, may have been deleted, or may belong to another organization." />;
    if (status === 403) return <ErrorState title="Access denied" message="This unit is outside the part of the organization you can access." />;
    return <ErrorState title="Could not load this unit" message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  }
  const detail = q.data;
  const unit = detail.unit!;
  const type = unit.type as OrgUnitType;
  const ancestors = detail.ancestors ?? [];
  const ancestorIds = ancestors.map((a) => a.id!);
  const canManage = access.hasInOrgUnit("org_unit.manage", unit.id, ancestorIds);
  const showFeatures = isInstitution(type) && access.hasInOrgUnit("feature.read", unit.id, ancestorIds);
  const childTypes = UNIT_TYPES.filter((t) => ALLOWED_PARENTS[t].includes(type));
  const dependents = detail.dependents;
  const inUse = !!dependents && ((dependents.children ?? 0) + (dependents.roleAssignments ?? 0) + (dependents.featureOverrides ?? 0) > 0);
  const childTotal = Object.values((detail.childCounts ?? {}) as Record<string, number>).reduce((a, b) => a + b, 0);
  const attributes = (unit.attributes ?? {}) as Record<string, string>;
  const accent = ((unit.branding ?? {}) as Record<string, string>)["accentColor"];

  return (
    <>
      <Link to={`/organization/${TYPE_ROUTES[type]}`} className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg xl:hidden">
        <ChevronLeft aria-hidden className="h-4 w-4" /> {labels.plural(type)}
      </Link>
      <div className="mb-3">
        <Breadcrumbs items={[{ label: "Organization", to: "/organization" },
          ...ancestors.map((a) => ({ label: a.name ?? "", to: `/organization/units/${a.id}` })), { label: unit.name ?? "" }]} />
      </div>
      <PageHeader title={unit.name ?? ""}
        description={<span className="inline-flex flex-wrap items-center gap-2"><Badge tone="info">{labels.singular(type)}</Badge><code>{unit.code}</code><OrgStatusBadge status={unit.status} /></span>}
        actions={canManage ? <>
          {childTypes.length > 0 && unit.status === "ACTIVE" && (
            <Menu trigger={<Button><Plus aria-hidden className="h-4 w-4" /> Add sub-unit</Button>}>
              {childTypes.map((t) => <MenuItem key={t} onSelect={() => setDialog({ create: t })}>{labels.singular(t)}</MenuItem>)}
            </Menu>
          )}
          <Button variant="secondary" onClick={() => setDialog("edit")}><Pencil aria-hidden className="h-4 w-4" /> Edit</Button>
          <Menu trigger={<Button variant="secondary" aria-label="More actions"><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
            <MenuItem onSelect={() => setDialog("move")}><ArrowRightLeft aria-hidden className="h-4 w-4" /> Move…</MenuItem>
            <MenuItem onSelect={() => setDialog("status")}><Power aria-hidden className="h-4 w-4" /> {unit.status === "ACTIVE" ? "Deactivate…" : "Activate…"}</MenuItem>
            {inUse ? (
              <Tooltip content="In use: deactivate instead" side="bottom">
                <span className="flex cursor-not-allowed items-center gap-2 px-3 py-2 text-muted"><Trash2 aria-hidden className="h-4 w-4" /> Delete (in use)</span>
              </Tooltip>
            ) : <MenuItem tone="danger" onSelect={() => setDialog("delete")}><Trash2 aria-hidden className="h-4 w-4" /> Delete…</MenuItem>}
          </Menu>
        </> : undefined} />

      <Tabs label="Unit sections" value={tab} onValueChange={(v) => setParams(v === "overview" ? {} : { tab: v }, { replace: true })}
        tabs={[{ value: "overview", label: "Overview" }, { value: "children", label: "Sub-units", count: childTotal },
          ...(showFeatures ? [{ value: "features", label: "Features" }] : []), { value: "history", label: "History" }]}>
        <TabPanel value="overview">
          <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <CardHeader title="Details" />
              <CardBody>
                <dl className="grid grid-cols-1 gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
                  <Item label="Type">{labels.singular(type)}</Item>
                  <Item label="Code"><code>{unit.code}</code></Item>
                  <Item label="Short name">{unit.shortName ?? "—"}</Item>
                  <Item label="Parent">{unit.parentId ? <Link to={`/organization/units/${unit.parentId}`} className="text-primary hover:underline">{unit.parentName}</Link> : "Top level"}</Item>
                  <Item label="City">{attributes["city"] ?? "—"}</Item>
                  <Item label="State">{attributes["state"] ?? "—"}</Item>
                  <Item label="Website">{attributes["website"] ? <a className="break-all text-primary hover:underline" href={attributes["website"]} target="_blank" rel="noopener noreferrer">{attributes["website"]}</a> : "—"}</Item>
                  <Item label="Contact email">{attributes["email"] ?? "—"}</Item>
                  {isInstitution(type) && <Item label="Accent colour">{accent ? <span className="inline-flex items-center gap-2"><span aria-hidden className="h-4 w-4 rounded border border-border" style={{ background: accent }} /><code>{accent}</code></span> : "—"}</Item>}
                  <Item label="Last changed">{formatDateTime(unit.updatedAt)}</Item>
                </dl>
              </CardBody>
            </Card>
            <Card>
              <CardHeader title="Usage" description="Units in use cannot be deleted" />
              <CardBody>
                <dl className="space-y-3 text-sm">
                  <Count label="Sub-units" value={dependents?.children} />
                  <Count label="Active sub-units" value={dependents?.activeChildren} />
                  <Count label="Role assignments" value={dependents?.roleAssignments} />
                  <Count label="Feature settings" value={dependents?.featureOverrides} />
                </dl>
              </CardBody>
            </Card>
          </div>
        </TabPanel>
        <TabPanel value="children"><Children parent={unit} canManage={canManage && unit.status === "ACTIVE"} childTypes={childTypes} onAdd={(t) => setDialog({ create: t })} /></TabPanel>
        {showFeatures && <TabPanel value="features"><FeatureConfigurationPanel orgUnitId={unit.id!} unitName={unit.name ?? ""} ancestorIds={ancestorIds} /></TabPanel>}
        <TabPanel value="history"><History id={unit.id!} /></TabPanel>
      </Tabs>

      {dialog === "edit" && <OrgUnitFormDialog open onOpenChange={(o) => !o && setDialog(null)} type={type} existing={detail} />}
      {dialog === "move" && <MoveDialog open onOpenChange={(o) => !o && setDialog(null)} detail={detail} />}
      {dialog === "status" && <StatusDialog open onOpenChange={(o) => !o && setDialog(null)} detail={detail} />}
      {dialog === "delete" && <DeleteDialog open onOpenChange={(o) => !o && setDialog(null)} detail={detail} />}
      {dialog && typeof dialog === "object" && <OrgUnitFormDialog open onOpenChange={(o) => !o && setDialog(null)} type={dialog.create} parentId={unit.id} />}
    </>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-muted">{label}</dt><dd className="mt-1 font-medium text-fg">{children}</dd></div>;
}

function Count({ label, value }: { label: string; value: number | undefined }) {
  return <div className="flex items-center justify-between"><dt className="text-muted">{label}</dt><dd className="tabular font-semibold">{value ?? 0}</dd></div>;
}

function Children({ parent, canManage, childTypes, onAdd }: { parent: OrgUnit; canManage: boolean; childTypes: OrgUnitType[]; onAdd: (t: OrgUnitType) => void }) {
  const api = useApi();
  const labels = useOrgLabels();
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["org-units", "children", parent.id, page],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/org-units", { params: { query: { parentId: parent.id!, page, size: 20, sort: "type" } } })),
  });
  const columns: Column<OrgUnit>[] = [
    { key: "name", header: "Name", cell: (u) => <Link to={`/organization/units/${u.id}`} className="font-medium text-primary hover:underline">{u.name}</Link> },
    { key: "type", header: "Type", cell: (u) => labels.singular(u.type as OrgUnitType) },
    { key: "code", header: "Code", cell: (u) => <code className="text-xs">{u.code}</code> },
    { key: "status", header: "Status", cell: (u) => <OrgStatusBadge status={u.status} /> },
  ];
  return (
    <Card>
      <CardHeader title="Sub-units" description={`Units directly under ${parent.name}`}
        actions={canManage && childTypes.length > 0 ? <Menu trigger={<Button variant="secondary" size="sm"><Plus aria-hidden className="h-4 w-4" /> Add</Button>}>
          {childTypes.map((t) => <MenuItem key={t} onSelect={() => onAdd(t)}>{labels.singular(t)}</MenuItem>)}
        </Menu> : undefined} />
      <DataTable caption="Sub-units" columns={columns} rows={q.data?.items} rowKey={(u) => u.id!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        page={q.data ? { page, totalPages: q.data.totalPages ?? 0, totalElements: q.data.totalElements ?? 0, onPageChange: setPage } : undefined}
        empty={{ title: "No sub-units", description: childTypes.length ? `Add a ${childTypes.map((t) => labels.singular(t).toLowerCase()).join(" or ")}.` : `A ${labels.singular(parent.type as OrgUnitType).toLowerCase()} has no sub-units.` }} />
    </Card>
  );
}

function History({ id }: { id: string }) {
  const api = useApi();
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["org-unit", id, "history", page],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/org-units/{id}/history", { params: { path: { id }, query: { page, size: 20 } } })),
  });
  const columns: Column<OrgUnitHistoryEntry>[] = [
    { key: "when", header: "When", cell: (h) => <span className="whitespace-nowrap">{formatDateTime(h.changedAt)}</span> },
    { key: "change", header: "Change", cell: (h) => <Badge tone={h.changeType === "DEACTIVATED" || h.changeType === "DELETED" ? "warning" : "info"}>{humanize(h.changeType)}</Badge> },
    { key: "by", header: "By", hideOnMobile: true, cell: (h) => h.changedByName ?? "System" },
    { key: "details", header: "Details", cell: (h) => <span className="line-clamp-2 max-w-md break-all text-xs text-muted">{describe(h)}</span> },
  ];
  return (
    <Card>
      <CardHeader title="History" description="Every change to this unit, newest first. Also recorded in the audit log." />
      <DataTable caption="Change history" columns={columns} rows={q.data?.items} rowKey={(h) => h.id!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        page={q.data ? { page, totalPages: q.data.totalPages ?? 0, totalElements: q.data.totalElements ?? 0, onPageChange: setPage } : undefined}
        empty={{ title: "No history yet" }} />
    </Card>
  );
}

function describe(h: OrgUnitHistoryEntry): string {
  const before = (h.before ?? {}) as Record<string, unknown>;
  const after = (h.after ?? {}) as Record<string, unknown>;
  const parts: string[] = [];
  if (h.changeType === "MOVED") parts.push(`${before["parentName"] ?? "top level"} → ${after["parentName"] ?? "top level"}`);
  else if (h.changeType === "UPDATED") {
    for (const k of ["code", "name", "shortName"]) if (before[k] !== after[k]) parts.push(`${k}: ${before[k] ?? "—"} → ${after[k] ?? "—"}`);
    if (JSON.stringify(before["attributes"]) !== JSON.stringify(after["attributes"])) parts.push("contact details changed");
    if (JSON.stringify(before["branding"]) !== JSON.stringify(after["branding"])) parts.push("branding changed");
  } else if (after["name"]) parts.push(String(after["name"]));
  if (h.reason) parts.push(`reason: ${h.reason}`);
  return parts.join(" · ") || "—";
}

export default OrgUnitDetailPage;
