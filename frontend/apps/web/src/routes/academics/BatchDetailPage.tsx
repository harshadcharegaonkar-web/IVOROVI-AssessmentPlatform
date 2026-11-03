import { unwrap, type SubjectOffering } from "@ivorovi/api-client";
import { Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, DataTable, Link, Menu, MenuItem, PageHeader, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { MoreHorizontal, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, Facts, StatusBadge, StatusDialog, useInvalidateAcademics } from "./AcademicsShared";
import { statusLabel } from "./academicsModel";
import { OfferingDialog } from "./OfferingDialog";

type BatchStatus = "PLANNED" | "ACTIVE" | "GRADUATED" | "INACTIVE";
const NEXT: Record<string, BatchStatus[]> = { PLANNED: ["ACTIVE", "INACTIVE"], ACTIVE: ["GRADUATED", "INACTIVE", "PLANNED"], GRADUATED: ["ACTIVE"], INACTIVE: ["ACTIVE"] };

/** A batch with its subject offerings. */
export function BatchDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [target, setTarget] = useState<BatchStatus | null>(null);
  const [offering, setOffering] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["academics", "batch", id], queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches/{id}", { params: { path: { id } } })) });
  const offerings = useQuery({ queryKey: ["academics", "offerings", { batchId: id, all: true }], enabled: q.isSuccess,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings", { params: { query: { batchId: id, size: 100, sort: "term" } } })) });
  const status = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/batches/{id}/status", { params: { path: { id } },
      body: { status: target!, version: q.data!.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: (b) => { invalidate(); setTarget(null); toast.show("success", `Now ${statusLabel(b.status).toLowerCase()}`, b.name); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what={labels.singular("BATCH")} onRetry={() => void q.refetch()} />;
  const b = q.data;
  const columns: Column<SubjectOffering>[] = [
    { key: "subject", header: labels.singular("SUBJECT"), cell: (o) => <span className="block min-w-0"><Link to={`/academics/offerings/${o.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{o.subjectCode} · {o.subjectName}</Link>
      <span className="block truncate text-xs text-muted">{o.academicYearName} · {o.termName}{o.section ? ` · Section ${o.section}` : ""}</span></span> },
    { key: "term", header: `${labels.singular("COURSE")} ${labels.singular("TERM").toLowerCase()}`, hideOnMobile: true, cell: (o) => <span className="tabular">{o.termNumber}</span> },
    { key: "credits", header: "Credits", hideOnMobile: true, cell: (o) => <span className="tabular">{o.credits}</span> },
    { key: "faculty", header: "Faculty", cell: (o) => o.primaryFaculty ?? <span className="text-muted">Not assigned</span> },
    { key: "status", header: "Status", cell: (o) => <StatusBadge status={o.status} /> },
  ];
  const canOffer = b.canManage && (b.status === "ACTIVE" || b.status === "PLANNED");
  const reasonRequired = target === "INACTIVE" || b.status === "GRADUATED";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("BATCH"), to: "/academics/batches" }, { label: b.name ?? "" }]} /></div>
      <PageHeader title={b.name ?? ""} description={<span className="inline-flex flex-wrap items-center gap-2"><code>{b.code}</code><StatusBadge status={b.status} /></span>}
        actions={b.canManage ? <>
          <ButtonLink to={`/academics/batches/${b.id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>
          <Menu trigger={<Button variant="secondary" aria-label="Change status"><MoreHorizontal aria-hidden className="h-4 w-4" /> Status</Button>}>
            {(NEXT[b.status ?? ""] ?? []).map((s) => <MenuItem key={s} onSelect={() => { setProblem(null); setTarget(s); }}>Mark {statusLabel(s).toLowerCase()}</MenuItem>)}
          </Menu>
        </> : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[
            { label: labels.singular("COURSE"), value: <Link to={`/academics/courses/${b.courseId}`} className="text-primary underline-offset-2 hover:underline">{b.courseName} ({b.courseCode})</Link> },
            { label: labels.singular("PROGRAM"), value: <Link to={`/academics/programs/${b.programId}`} className="text-primary underline-offset-2 hover:underline">{b.programName}</Link> },
            { label: labels.singular("DEPARTMENT"), value: b.departmentName },
            { label: "Academic year", value: b.academicYearName },
            { label: "Admission year", value: b.admissionYear }, { label: "Expected graduation", value: b.expectedGraduationYear },
            { label: "Section", value: b.section || "—" }, { label: "Capacity", value: b.capacity ?? "—" },
          ]} />
        </CardBody></Card>
        <Card>
          <CardHeader title="Subject offerings" description={`${labels.plural("SUBJECT")} delivered to this ${labels.singular("BATCH").toLowerCase()} in each calendar ${labels.singular("TERM").toLowerCase()}.`}
            actions={canOffer ? <Button size="sm" onClick={() => setOffering(true)}><Plus aria-hidden className="h-4 w-4" /> Offer a {labels.singular("SUBJECT").toLowerCase()}</Button> : undefined} />
          <DataTable caption="Subject offerings" columns={columns} rows={offerings.data?.items} rowKey={(o) => o.id!} loading={offerings.isPending}
            error={offerings.isError ? errorInfo(offerings.error) : null} onRetry={() => void offerings.refetch()}
            empty={{ title: "No offerings yet", description: `Offer ${labels.plural("SUBJECT").toLowerCase()} from the ${labels.singular("COURSE").toLowerCase()}'s curriculum.` }} />
        </Card>
      </div>
      {offering && <OfferingDialog batch={b} onClose={() => setOffering(false)} />}
      <StatusDialog open={!!target} onOpenChange={(o) => !o && setTarget(null)} busy={status.isPending} error={problem} reasonRequired={reasonRequired}
        title={`Mark ${b.name} ${statusLabel(target ?? "").toLowerCase()}?`} confirmLabel="Change status" tone={target === "INACTIVE" ? "danger" : "primary"}
        description={target === "GRADUATED" ? "The batch and its history are kept." : target === "INACTIVE" ? "No new offerings can be added while it is inactive; history is kept." : "Status changes are recorded in the audit trail."}
        onConfirm={(r) => status.mutate(r)} />
    </>
  );
}

export default BatchDetailPage;
