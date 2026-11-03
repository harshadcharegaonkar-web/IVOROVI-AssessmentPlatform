import { unwrap, type CurriculumEntry } from "@ivorovi/api-client";
import { Alert, Badge, Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, DataTable, Field, Input, Link, Modal, PageHeader, Select, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Pencil, Plus, Power, X } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, Facts, StatusBadge, StatusDialog, useInvalidateAcademics } from "./AcademicsShared";
import { CATEGORIES, DELIVERY, labelOf } from "./academicsModel";
import { useSubjectOptions } from "./useAcademicOptions";

/** Subject master: attributes, prerequisites (no cycles) and where it is taught. */
export function SubjectDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [statusOpen, setStatusOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["academics", "subject", id], queryFn: async () => unwrap(await api.GET("/api/v1/academics/subjects/{id}", { params: { path: { id } } })) });
  const status = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/subjects/{id}/status", { params: { path: { id } },
      body: { status: q.data!.subject!.status === "ACTIVE" ? "INACTIVE" : "ACTIVE", version: q.data!.subject!.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: (d) => { invalidate(); setStatusOpen(false); toast.show("success", d.subject?.status === "ACTIVE" ? "Activated" : "Deactivated", d.subject?.name); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const removePrereq = useMutation({
    mutationFn: async (prerequisiteId: string) => unwrap(await api.DELETE("/api/v1/academics/subjects/{id}/prerequisites/{prerequisiteId}", { params: { path: { id, prerequisiteId } } })),
    onSuccess: () => { invalidate(); toast.show("success", "Prerequisite removed"); },
    onError: (e) => toast.show("danger", "Could not remove", errorInfo(e).message),
  });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what={labels.singular("SUBJECT")} onRetry={() => void q.refetch()} />;
  const s = q.data.subject!;
  const active = s.status === "ACTIVE";
  const columns: Column<CurriculumEntry>[] = [
    { key: "course", header: labels.singular("COURSE"), cell: (e) => <span className="block min-w-0"><Link to={`/academics/courses/${e.courseId}`} className="block truncate text-primary underline-offset-2 hover:underline">{e.courseName}</Link>
      <span className="block truncate text-xs text-muted">{e.programName}</span></span> },
    { key: "term", header: labels.singular("TERM"), cell: (e) => <span className="tabular">{e.termNumber}</span> },
    { key: "elective", header: "Type", cell: (e) => e.elective ? <Badge>Elective</Badge> : "Mandatory" },
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("SUBJECT"), to: "/academics/subjects" }, { label: s.code ?? "" }]} /></div>
      <PageHeader title={`${s.code} · ${s.name}`} description={<span className="inline-flex flex-wrap items-center gap-2"><Badge>{labelOf(CATEGORIES, s.category)}</Badge><StatusBadge status={s.status} /></span>}
        actions={s.canManage ? <>
          <ButtonLink to={`/academics/subjects/${s.id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>
          <Button variant="secondary" onClick={() => { setProblem(null); setStatusOpen(true); }}><Power aria-hidden className="h-4 w-4" /> {active ? "Deactivate" : "Activate"}</Button>
        </> : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[
            { label: "Credits", value: s.credits },
            { label: "Theory or practical", value: labelOf(DELIVERY, s.delivery) },
            { label: "Default", value: s.elective ? "Elective" : "Mandatory" },
            { label: "Owned by", value: s.ownerName },
            { label: "Description", value: s.description || "—" },
            { label: "Last changed", value: formatDateTime(s.updatedAt) },
          ]} />
        </CardBody></Card>
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader title="Prerequisites" description={`${labels.plural("SUBJECT")} that must be completed first.`}
              actions={s.canManage ? <Button size="sm" variant="secondary" onClick={() => setAdding(true)}><Plus aria-hidden className="h-4 w-4" /> Add</Button> : undefined} />
            <CardBody>
              {(q.data.prerequisites ?? []).length === 0 ? <p className="text-sm text-muted">None.</p> : (
                <ul className="space-y-2">{q.data.prerequisites!.map((p) => (
                  <li key={p.id} className="flex items-center justify-between gap-2 text-sm">
                    <Link to={`/academics/subjects/${p.id}`} className="truncate text-primary underline-offset-2 hover:underline">{p.code} · {p.name}</Link>
                    {s.canManage && <button type="button" aria-label={`Remove prerequisite ${p.code}`} className="rounded p-1 text-muted hover:text-danger" onClick={() => removePrereq.mutate(p.id!)}><X aria-hidden className="h-4 w-4" /></button>}
                  </li>))}</ul>
              )}
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Required by" description={`${labels.plural("SUBJECT")} that list this one as a prerequisite.`} />
            <CardBody>
              {(q.data.requiredBy ?? []).length === 0 ? <p className="text-sm text-muted">None.</p> : (
                <ul className="space-y-2">{q.data.requiredBy!.map((p) => <li key={p.id} className="text-sm"><Link to={`/academics/subjects/${p.id}`} className="text-primary underline-offset-2 hover:underline">{p.code} · {p.name}</Link></li>)}</ul>
              )}
            </CardBody>
          </Card>
        </div>
        <Card>
          <CardHeader title="Taught in" description={`The same master is reused by each ${labels.singular("COURSE").toLowerCase()} and ${labels.singular("TERM").toLowerCase()} below.`} />
          <DataTable caption="Curricula using this subject" columns={columns} rows={q.data.curriculum} rowKey={(e) => e.id!}
            empty={{ title: "Not in any curriculum yet", description: `Add it from a ${labels.singular("COURSE").toLowerCase()}'s curriculum.` }} />
        </Card>
      </div>
      {adding && <AddPrerequisiteDialog subjectId={s.id!} existing={(q.data.prerequisites ?? []).map((p) => p.id!)} onClose={() => setAdding(false)} />}
      <StatusDialog open={statusOpen} onOpenChange={setStatusOpen} busy={status.isPending} error={problem} reasonRequired={active}
        title={active ? `Deactivate ${s.code}?` : `Activate ${s.code}?`} tone={active ? "danger" : "primary"} confirmLabel={active ? "Deactivate" : "Activate"}
        description={active ? "Existing curricula and offerings keep it; it cannot be added to curricula or offered again while inactive." : "It becomes available again."}
        onConfirm={(r) => status.mutate(r)} />
    </>
  );
}

function AddPrerequisiteDialog({ subjectId, existing, onClose }: { subjectId: string; existing: string[]; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [search, setSearch] = useState("");
  const [choice, setChoice] = useState("");
  const [error, setError] = useState<string | null>(null);
  const options = useSubjectOptions(search).items.filter((o) => o.id !== subjectId && !existing.includes(o.id!));
  const add = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/academics/subjects/{id}/prerequisites", { params: { path: { id: subjectId } }, body: { prerequisiteId: choice } })),
    onSuccess: () => { invalidate(); toast.show("success", "Prerequisite added"); onClose(); },
    onError: (e) => setError(errorInfo(e).message),
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Add a prerequisite" description="Circular prerequisites are refused."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={add.isPending} disabled={!choice} onClick={() => { setError(null); add.mutate(); }}>Add</Button></>}>
      <div className="space-y-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Find">{(p) => <Input {...p} type="search" value={search} maxLength={100} placeholder="Code or name" onChange={(e) => setSearch(e.target.value)} />}</Field>
        <Field label="Prerequisite">{(p) => <Select {...p} value={choice} onChange={(e) => setChoice(e.target.value)}><option value="">Choose…</option>
          {options.map((o) => <option key={o.id} value={o.id}>{o.code} · {o.name}</option>)}</Select>}</Field>
      </div>
    </Modal>
  );
}

export default SubjectDetailPage;
