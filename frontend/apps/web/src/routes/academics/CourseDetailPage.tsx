import { unwrap, type Batch } from "@ivorovi/api-client";
import { Alert, Badge, Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, ConfirmDialog, DataTable, Field, Input, Link, Modal, PageHeader, Select, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Pencil, Plus, Power, X } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { ancestorIds, useOrgTree } from "../../session/useOrgTree";
import { DetailError, Facts, StatusBadge, StatusDialog, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { CATEGORIES, groupByTerm, labelOf } from "./academicsModel";
import { useSubjectOptions } from "./useAcademicOptions";

/** Course details: the curriculum term by term (subject masters placed into terms), and its batches. */
export function CourseDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const { access } = useSession();
  const tree = useOrgTree();
  const invalidate = useInvalidateAcademics();
  const [statusOpen, setStatusOpen] = useState(false);
  const [addTerm, setAddTerm] = useState<number | null>(null);
  const [removing, setRemoving] = useState<{ id: string; label: string } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["academics", "course", id], queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses/{id}", { params: { path: { id } } })) });
  const curriculum = useQuery({ queryKey: ["academics", "curriculum", id], enabled: q.isSuccess,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses/{id}/curriculum", { params: { path: { id } } })) });
  const batches = useQuery({ queryKey: ["academics", "batches", { courseId: id, all: true }], enabled: q.isSuccess,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches", { params: { query: { courseId: id, size: 100 } } })) });
  const status = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/courses/{id}/status", { params: { path: { id } },
      body: { status: q.data!.status === "ACTIVE" ? "INACTIVE" : "ACTIVE", version: q.data!.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: (c) => { invalidate(); setStatusOpen(false); toast.show("success", c.status === "ACTIVE" ? "Activated" : "Deactivated", c.name); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const remove = useMutation({
    mutationFn: async (entryId: string) => unwrap(await api.DELETE("/api/v1/academics/courses/{id}/curriculum/{entryId}", { params: { path: { id, entryId } } })),
    onSuccess: () => { invalidate(); setRemoving(null); toast.show("success", "Removed from the curriculum"); },
    onError: (e) => { setRemoving(null); toast.show("danger", "Could not remove", errorInfo(e).message); },
  });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what={labels.singular("COURSE")} onRetry={() => void q.refetch()} />;
  const c = q.data;
  const active = c.status === "ACTIVE";
  const canCurriculum = active && access.hasInOrgUnit("subject.manage", c.departmentId, ancestorIds(tree.data, c.departmentId));
  const canBatch = active && access.hasInOrgUnit("batch.manage", c.departmentId, ancestorIds(tree.data, c.departmentId));
  const terms = groupByTerm(curriculum.data ?? [], c.effectiveTerms ?? 0);
  const term = labels.singular("TERM");
  const batchColumns: Column<Batch>[] = [
    { key: "name", header: labels.singular("BATCH"), cell: (b) => <span className="block min-w-0"><Link to={`/academics/batches/${b.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{b.name}</Link>
      <span className="block truncate text-xs text-muted">{b.code}{b.section ? ` · Section ${b.section}` : ""}</span></span> },
    { key: "year", header: "Admitted", cell: (b) => <span className="tabular">{b.admissionYear}</span> },
    { key: "grad", header: "Graduates", hideOnMobile: true, cell: (b) => <span className="tabular">{b.expectedGraduationYear}</span> },
    { key: "offerings", header: "Offerings", hideOnMobile: true, cell: (b) => <span className="tabular">{b.offeringCount}</span> },
    { key: "status", header: "Status", cell: (b) => <StatusBadge status={b.status} /> },
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("PROGRAM"), to: "/academics/programs" },
        { label: c.programName ?? "", to: `/academics/programs/${c.programId}` }, { label: c.name ?? "" }]} /></div>
      <PageHeader title={c.name ?? ""} description={<span className="inline-flex flex-wrap items-center gap-2"><code>{c.code}</code><StatusBadge status={c.status} /></span>}
        actions={c.canManage ? <>
          <ButtonLink to={`/academics/courses/${c.id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>
          <Button variant="secondary" onClick={() => { setProblem(null); setStatusOpen(true); }}><Power aria-hidden className="h-4 w-4" /> {active ? "Deactivate" : "Activate"}</Button>
        </> : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[
            { label: labels.singular("PROGRAM"), value: <Link to={`/academics/programs/${c.programId}`} className="text-primary underline-offset-2 hover:underline">{c.programName} ({c.programCode})</Link> },
            { label: labels.singular("DEPARTMENT"), value: c.departmentName },
            { label: "Specialization", value: c.specialization || "—" },
            { label: labels.plural("TERM"), value: `${c.effectiveTerms}${c.totalTerms == null ? ` (from the ${labels.singular("PROGRAM").toLowerCase()})` : ""}` },
            { label: "Description", value: c.description || "—" },
          ]} />
        </CardBody></Card>

        <Card>
          <CardHeader title="Curriculum" description={`Which ${labels.plural("SUBJECT").toLowerCase()} are taught in each ${term.toLowerCase()}. ${labels.plural("SUBJECT")} are shared masters, never copies.`} />
          {curriculum.isPending ? <CardBody><Skeleton className="h-32 w-full" /></CardBody> : curriculum.isError ? <CardBody><Alert tone="danger">{errorInfo(curriculum.error).message}</Alert></CardBody> : (
            <ol className="divide-y divide-border">
              {terms.map((t) => (
                <li key={t.term} className="px-5 py-4 sm:px-6" data-testid={`curriculum-term-${t.term}`}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h3 className="text-sm font-semibold text-fg">{term} {t.term} <span className="font-normal text-muted">· {t.entries.length} {t.entries.length === 1 ? labels.singular("SUBJECT").toLowerCase() : labels.plural("SUBJECT").toLowerCase()} · {t.entries.reduce((a, e) => a + (e.credits ?? 0), 0)} credits</span></h3>
                    {canCurriculum && <Button size="sm" variant="ghost" onClick={() => setAddTerm(t.term)}><Plus aria-hidden className="h-4 w-4" /> Add {labels.singular("SUBJECT").toLowerCase()}<span className="sr-only"> to {term} {t.term}</span></Button>}
                  </div>
                  {t.entries.length > 0 && (
                    <ul className="mt-2 flex flex-wrap gap-2">
                      {t.entries.map((e) => (
                        <li key={e.id} className="inline-flex max-w-full items-center gap-2 rounded-md border border-border bg-surface-muted px-2 py-1 text-sm">
                          <Link to={`/academics/subjects/${e.subjectId}`} className="truncate text-primary underline-offset-2 hover:underline">{e.subjectCode} · {e.subjectName}</Link>
                          <span className="text-xs text-muted-strong tabular">{e.credits} cr</span>
                          {e.elective && <Badge>Elective</Badge>}
                          {canCurriculum && <button type="button" className="rounded p-0.5 text-muted hover:text-danger" aria-label={`Remove ${e.subjectCode} from ${term} ${t.term}`}
                            onClick={() => setRemoving({ id: e.id!, label: `${e.subjectCode} from ${term} ${t.term}` })}><X aria-hidden className="h-4 w-4" /></button>}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ol>
          )}
        </Card>

        <Card>
          <CardHeader title={labels.plural("BATCH")} actions={canBatch ? <ButtonLink to={`/academics/batches/new?course=${c.id}`} variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {labels.singular("BATCH").toLowerCase()}</ButtonLink> : undefined} />
          <DataTable caption={labels.plural("BATCH")} columns={batchColumns} rows={batches.data?.items} rowKey={(b) => b.id!} loading={batches.isPending}
            error={batches.isError ? errorInfo(batches.error) : null} onRetry={() => void batches.refetch()}
            empty={{ title: `No ${labels.plural("BATCH").toLowerCase()} yet`, description: `A ${labels.singular("BATCH").toLowerCase()} is an intake of students admitted in a year.` }} />
        </Card>
      </div>

      {addTerm !== null && <AddSubjectDialog courseId={c.id!} term={addTerm} terms={c.effectiveTerms ?? 1} onClose={() => setAddTerm(null)} />}
      <ConfirmDialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)} title="Remove from the curriculum?" tone="danger" confirmLabel="Remove"
        busy={remove.isPending} onConfirm={() => removing && remove.mutate(removing.id)}
        description={`Remove ${removing?.label}? This is refused if it has already been offered to a ${labels.singular("BATCH").toLowerCase()}.`} />
      <StatusDialog open={statusOpen} onOpenChange={setStatusOpen} busy={status.isPending} error={problem} reasonRequired={active}
        title={active ? `Deactivate ${c.name}?` : `Activate ${c.name}?`} tone={active ? "danger" : "primary"} confirmLabel={active ? "Deactivate" : "Activate"}
        description={active ? `Its curriculum, ${labels.plural("BATCH").toLowerCase()} and history are kept; no new ${labels.plural("BATCH").toLowerCase()} or offerings can be created while it is inactive.` : "It becomes available again."}
        onConfirm={(r) => status.mutate(r)} />
    </>
  );
}

function AddSubjectDialog({ courseId, term, terms, onClose }: { courseId: string; term: number; terms: number; onClose: () => void }) {
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [search, setSearch] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [termNumber, setTermNumber] = useState(String(term));
  const [elective, setElective] = useState("");
  const [error, setError] = useState<string | null>(null);
  const subjects = useSubjectOptions(search);
  const add = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/academics/courses/{id}/curriculum", { params: { path: { id: courseId } }, headers: { "Idempotency-Key": key.get() },
      body: { subjectId, termNumber: Number(termNumber), ...(elective ? { elective: elective === "yes" } : {}) } })),
    onSuccess: () => { invalidate(); toast.show("success", `${labels.singular("SUBJECT")} added`); onClose(); },
    onError: (e) => { key.rotate(); setError(errorInfo(e).message); },
  });
  const noun = labels.singular("SUBJECT");
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Add a ${noun.toLowerCase()} to the curriculum`}
      description={`Only active ${labels.plural("SUBJECT").toLowerCase()} of this institution (or its ${labels.singular("UNIVERSITY").toLowerCase()}) can be used.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={add.isPending} disabled={!subjectId} onClick={() => { setError(null); add.mutate(); }}>Add</Button></>}>
      <div className="space-y-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={`Find a ${noun.toLowerCase()}`}>{(p) => <Input {...p} type="search" value={search} maxLength={100} placeholder="Code or name" onChange={(e) => setSearch(e.target.value)} />}</Field>
        <Field label={noun} hint={subjects.loading ? "Loading…" : subjects.items.length === 0 ? "No matching active subjects." : undefined}>
          {(p) => <Select {...p} value={subjectId} onChange={(e) => setSubjectId(e.target.value)}><option value="">Choose…</option>
            {subjects.items.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name} ({s.credits} cr, {labelOf(CATEGORIES, s.category)})</option>)}</Select>}
        </Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={labels.singular("TERM")}>{(p) => <Select {...p} value={termNumber} onChange={(e) => setTermNumber(e.target.value)}>
            {Array.from({ length: terms }, (_, i) => <option key={i + 1} value={i + 1}>{labels.singular("TERM")} {i + 1}</option>)}</Select>}</Field>
          <Field label="Elective here?">{(p) => <Select {...p} value={elective} onChange={(e) => setElective(e.target.value)}>
            <option value="">As defined for the {noun.toLowerCase()}</option><option value="yes">Elective</option><option value="no">Mandatory</option></Select>}</Field>
        </div>
      </div>
    </Modal>
  );
}

export default CourseDetailPage;
