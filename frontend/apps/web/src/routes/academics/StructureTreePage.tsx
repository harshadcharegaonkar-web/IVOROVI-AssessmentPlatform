import { unwrap } from "@ivorovi/api-client";
import { Alert, Badge, Card, CardBody, EmptyState, ErrorState, Field, FilterBar, PageHeader, Select, Skeleton, Switch, TreeView } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { StatusBadge, UnitChoices } from "./AcademicsShared";
import { treeNodes, type StructureNode } from "./academicsModel";

/** Program → Course → Batch tree of the part of the organization the caller can see. */
export function StructureTreePage() {
  const api = useApi();
  const navigate = useNavigate();
  const labels = useOrgLabels();
  const [owner, setOwner] = useState("");
  const [inactive, setInactive] = useState(false);
  const q = useQuery({ queryKey: ["academics", "tree", owner, inactive],
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/tree", { params: { query: { includeInactive: inactive, ...(owner ? { ownerOrgUnitId: owner } : {}) } } })) });
  const nodes = treeNodes(q.data);
  const kind = { program: labels.singular("PROGRAM"), course: labels.singular("COURSE"), batch: labels.singular("BATCH") };
  return (
    <>
      <PageHeader title="Academic structure" description={`${labels.plural("PROGRAM")} → ${labels.plural("COURSE")} → ${labels.plural("BATCH")}. Select an entry to open it.`} />
      <Card>
        <FilterBar>
          <Field label="Owned by" className="w-full sm:w-60">
            {(p) => <Select {...p} value={owner} onChange={(e) => setOwner(e.target.value)}><option value="">Everything I can see</option>
              <UnitChoices permission="academics.read" types={["UNIVERSITY", "COLLEGE"]} /></Select>}
          </Field>
          <div className="flex items-center gap-2 pb-2 text-sm">
            <Switch id="inactive" checked={inactive} onCheckedChange={setInactive} label="Include inactive entries" /><label htmlFor="inactive">Include inactive</label>
          </div>
        </FilterBar>
        <CardBody>
          {q.isPending ? <div className="space-y-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-8 w-full" />)}</div>
            : q.isError ? <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />
            : nodes.length === 0 ? <EmptyState title={`No ${labels.plural("PROGRAM").toLowerCase()} to show`} description="Create a program, or include inactive entries." />
            : <>
                {q.data.truncated && <Alert tone="warning" className="mb-3">Only the first 200 programs are shown. Filter by owner to see the rest.</Alert>}
                <TreeView<StructureNode> label="Academic structure" items={nodes} defaultExpandedDepth={2} onSelect={(n) => void navigate(n.to)}
                  renderItem={(n) => (
                    <span className="flex min-w-0 flex-wrap items-center gap-2">
                      <Badge tone={n.kind === "program" ? "info" : "neutral"}>{kind[n.kind]}</Badge>
                      <span className="truncate font-medium">{n.label}</span>
                      <span className="truncate text-xs text-muted">{n.meta}</span>
                      {n.status !== "ACTIVE" && <StatusBadge status={n.status} />}
                    </span>)} />
              </>}
        </CardBody>
      </Card>
    </>
  );
}

export default StructureTreePage;
