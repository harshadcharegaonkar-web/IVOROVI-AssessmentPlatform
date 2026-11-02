import type { OrgUnitType } from "@ivorovi/api-client";
import { Badge, Button, ButtonLink, Card, CardHeader, EmptyState, ErrorState, Input, PageHeader, Skeleton, TreeView } from "@ivorovi/ui";
import { Plus, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useOrgTree } from "../../session/useOrgTree";
import { OrgUnitFormDialog } from "./OrgUnitDialogs";
import { OrgUnitSummaryPanel } from "./OrgUnitSummaryPanel";

/** Organization hierarchy overview: tree on the left, selected unit on the right (stacked on small screens). */
export function OrgHierarchyPage() {
  const tree = useOrgTree();
  const labels = useOrgLabels();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState("");
  const [creating, setCreating] = useState<OrgUnitType | null>(null);
  const selectedId = params.get("unit");
  const canCreateTop = access.has("org_unit.manage");

  const items = useMemo(() => {
    const nodes = (tree.data ?? []).map((n) => ({ ...n, id: n.id!, parentId: n.parentId ?? null, label: n.name ?? "" }));
    const term = filter.trim().toLowerCase();
    if (!term) return nodes;
    // keep matches and their ancestors so the tree stays navigable
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const keep = new Set<string>();
    for (const n of nodes) {
      if (n.label.toLowerCase().includes(term) || (n.code ?? "").toLowerCase().includes(term)) {
        let cur: typeof n | undefined = n;
        while (cur && !keep.has(cur.id)) { keep.add(cur.id); cur = cur.parentId ? byId.get(cur.parentId) : undefined; }
      }
    }
    return nodes.filter((n) => keep.has(n.id));
  }, [tree.data, filter]);

  return (
    <>
      <PageHeader title="Organization hierarchy"
        description={`${labels.plural("UNIVERSITY")}, ${labels.plural("COLLEGE").toLowerCase()}, ${labels.plural("CAMPUS").toLowerCase()} and ${labels.plural("DEPARTMENT").toLowerCase()} in your organization.`}
        actions={canCreateTop ? <>
          <Button variant="secondary" onClick={() => setCreating("COLLEGE")}><Plus aria-hidden className="h-4 w-4" /> {labels.singular("COLLEGE")}</Button>
          <Button onClick={() => setCreating("UNIVERSITY")}><Plus aria-hidden className="h-4 w-4" /> {labels.singular("UNIVERSITY")}</Button>
        </> : undefined} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <Card className="min-w-0">
          <CardHeader title="Structure" description={tree.data ? `${tree.data.length} units` : undefined} />
          <div className="border-b border-border p-4">
            <label className="relative block">
              <span className="sr-only">Filter the hierarchy</span>
              <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <Input type="search" placeholder="Filter by name or code" value={filter} onChange={(e) => setFilter(e.target.value)} className="pl-9" />
            </label>
          </div>
          <div className="max-h-[60vh] overflow-y-auto p-3">
            {tree.isPending ? <div className="space-y-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-8 w-full" />)}</div>
              : tree.isError ? <ErrorState message={errorInfo(tree.error).message} onRetry={() => void tree.refetch()} />
              : items.length === 0 ? (
                <EmptyState title={filter ? "No units match" : "Your organization has no units yet"}
                  description={filter ? "Try another name or code." : `Start by adding a ${labels.singular("UNIVERSITY").toLowerCase()} or a stand-alone ${labels.singular("COLLEGE").toLowerCase()}.`} />
              ) : (
                <TreeView key={filter} items={items} label="Organization hierarchy" selectedId={selectedId} defaultExpandedDepth={filter ? 10 : 2}
                  onSelect={(n) => setParams({ unit: n.id })}
                  renderItem={(n) => (
                    <span className="flex min-w-0 items-center gap-2">
                      <span className="truncate">{n.label}</span>
                      <span className="shrink-0 text-xs text-muted-strong">{labels.singular(n.type as OrgUnitType)}</span>
                      {n.status !== "ACTIVE" && <Badge>Inactive</Badge>}
                    </span>
                  )} />
              )}
          </div>
        </Card>
        <div className="min-w-0">
          {selectedId ? <OrgUnitSummaryPanel id={selectedId} /> : (
            <Card><EmptyState title="Select a unit" description="Choose a unit in the structure to see its details, path and sub-units."
              action={<ButtonLink to="/organization/colleges">Browse all {labels.plural("COLLEGE").toLowerCase()}</ButtonLink>} /></Card>
          )}
        </div>
      </div>
      {creating && <OrgUnitFormDialog open onOpenChange={(o) => !o && setCreating(null)} type={creating} />}
    </>
  );
}

export default OrgHierarchyPage;
