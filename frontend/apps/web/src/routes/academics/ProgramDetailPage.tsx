import { unwrap, type Course } from "@ivorovi/api-client";
import { Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, DataTable, Link, PageHeader, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Pencil, Plus, Power } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, Facts, StatusBadge, StatusDialog, useInvalidateAcademics } from "./AcademicsShared";
import { labelOf, QUALIFICATION_LEVELS, TERM_SYSTEMS } from "./academicsModel";

export function ProgramDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [dialog, setDialog] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["academics", "program", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/programs/{id}", { params: { path: { id } } })) });
  const courses = useQuery({ queryKey: ["academics", "courses", { programId: id, all: true }], enabled: q.isSuccess,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses", { params: { query: { programId: id, size: 100, sort: "name" } } })) });
  const status = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/programs/{id}/status", { params: { path: { id } },
      body: { status: q.data!.status === "ACTIVE" ? "INACTIVE" : "ACTIVE", version: q.data!.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: (p) => { invalidate(); setDialog(false); toast.show("success", p.status === "ACTIVE" ? "Activated" : "Deactivated", p.name); },
    onError: (e) => setProblem(errorInfo(e).message),
  });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what={labels.singular("PROGRAM")} onRetry={() => void q.refetch()} />;
  const p = q.data;
  const active = p.status === "ACTIVE";
  const columns: Column<Course>[] = [
    { key: "name", header: labels.singular("COURSE"), cell: (c) => (
      <span className="block min-w-0"><Link to={`/academics/courses/${c.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{c.name}</Link>
        <span className="block truncate text-xs text-muted">{c.code}{c.specialization ? ` · ${c.specialization}` : ""}</span></span>) },
    { key: "department", header: labels.singular("DEPARTMENT"), hideOnMobile: true, cell: (c) => c.departmentName },
    { key: "terms", header: labels.plural("TERM"), hideOnMobile: true, cell: (c) => <span className="tabular">{c.effectiveTerms}</span> },
    { key: "batches", header: labels.plural("BATCH"), cell: (c) => <span className="tabular">{c.batchCount}</span> },
    { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("PROGRAM"), to: "/academics/programs" }, { label: p.name ?? "" }]} /></div>
      <PageHeader title={p.name ?? ""} description={<span className="inline-flex flex-wrap items-center gap-2"><code>{p.code}</code><StatusBadge status={p.status} /></span>}
        actions={p.canManage ? <>
          <ButtonLink to={`/academics/programs/${p.id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>
          <Button variant="secondary" onClick={() => { setProblem(null); setDialog(true); }}><Power aria-hidden className="h-4 w-4" /> {active ? "Deactivate" : "Activate"}</Button>
        </> : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[
            { label: "Qualification level", value: labelOf(QUALIFICATION_LEVELS, p.qualificationLevel) },
            { label: "Awarded qualification", value: p.awardedQualification || "—" },
            { label: "Owned by", value: `${p.ownerName} (${labels.singular((p.ownerType ?? "COLLEGE") as "COLLEGE")})` },
            { label: "Duration", value: `${p.durationYears} years` },
            { label: "Term system", value: labelOf(TERM_SYSTEMS, p.termSystem) },
            { label: `Total ${labels.plural("TERM").toLowerCase()}`, value: p.totalTerms },
            { label: "Description", value: p.description || "—" },
            { label: "Last changed", value: formatDateTime(p.updatedAt) },
          ]} />
        </CardBody></Card>
        <Card>
          <CardHeader title={labels.plural("COURSE")} description={`Branches or specializations of this ${labels.singular("PROGRAM").toLowerCase()}.`}
            actions={p.canManage && active ? <ButtonLink to={`/academics/courses/new?program=${p.id}`} variant="primary"><Plus aria-hidden className="h-4 w-4" /> Add {labels.singular("COURSE").toLowerCase()}</ButtonLink> : undefined} />
          <DataTable caption={labels.plural("COURSE")} columns={columns} rows={courses.data?.items} rowKey={(c) => c.id!} loading={courses.isPending}
            error={courses.isError ? errorInfo(courses.error) : null} onRetry={() => void courses.refetch()}
            empty={{ title: `No ${labels.plural("COURSE").toLowerCase()} yet`, description: `Add a ${labels.singular("COURSE").toLowerCase()} to define branches and their curriculum.` }} />
        </Card>
      </div>
      <StatusDialog open={dialog} onOpenChange={setDialog} busy={status.isPending} error={problem} reasonRequired={active}
        title={active ? `Deactivate ${p.name}?` : `Activate ${p.name}?`} tone={active ? "danger" : "primary"} confirmLabel={active ? "Deactivate" : "Activate"}
        description={active ? `Existing ${labels.plural("COURSE").toLowerCase()}, ${labels.plural("BATCH").toLowerCase()} and history are kept; no new ${labels.plural("COURSE").toLowerCase()} can be added while it is inactive.` : "It becomes available again."}
        onConfirm={(r) => status.mutate(r)} />
    </>
  );
}

export default ProgramDetailPage;
