import { unwrap, type Batch } from "@ivorovi/api-client";
import { Alert, Button, Field, Input, Modal, Select, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { formatRange } from "./academicsModel";
import { useTermOptions } from "./useAcademicOptions";

/**
 * Offer a subject to a batch in a calendar term. Only subjects in the course's curriculum for the chosen course
 * term can be offered, so subject masters are never duplicated.
 */
export function OfferingDialog({ batch, onClose }: { batch: Batch; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const navigate = useNavigate();
  const labels = useOrgLabels();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [termId, setTermId] = useState("");
  const [termNumber, setTermNumber] = useState("1");
  const [subjectId, setSubjectId] = useState("");
  const [section, setSection] = useState(batch.section ?? "");
  const [credits, setCredits] = useState("");
  const [error, setError] = useState<string | null>(null);
  const terms = useTermOptions().filter((t) => t.status !== "COMPLETED");
  const course = useQuery({ queryKey: ["academics", "course", batch.courseId], queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses/{id}", { params: { path: { id: batch.courseId! } } })) });
  const curriculum = useQuery({ queryKey: ["academics", "curriculum", batch.courseId], queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses/{id}/curriculum", { params: { path: { id: batch.courseId! } } })) });
  const subjects = (curriculum.data ?? []).filter((e) => String(e.termNumber) === termNumber);
  const create = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/academics/subject-offerings", { headers: { "Idempotency-Key": key.get() },
      body: { batchId: batch.id!, termId, termNumber: Number(termNumber), subjectId, ...(section.trim() ? { section: section.trim() } : {}), ...(credits ? { credits: Number(credits) } : {}) } })),
    onSuccess: (o) => { invalidate(); toast.show("success", "Subject offered", `${o.subjectCode} · ${o.termName}`); onClose(); void navigate(`/academics/offerings/${o.id}`); },
    onError: (e) => { key.rotate(); setError(errorInfo(e).message); },
  });
  const term = labels.singular("TERM");
  return (
    <Modal open size="lg" onOpenChange={(o) => !o && onClose()} title={`Offer a ${labels.singular("SUBJECT").toLowerCase()} to ${batch.name}`}
      description={`Choose the calendar ${term.toLowerCase()}, the ${labels.singular("COURSE").toLowerCase()} ${term.toLowerCase()} the batch is in, and a ${labels.singular("SUBJECT").toLowerCase()} of that ${term.toLowerCase()}'s curriculum.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={create.isPending} disabled={!termId || !subjectId} onClick={() => { setError(null); create.mutate(); }}>Offer</Button></>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {error && <Alert tone="danger" className="sm:col-span-2">{error}</Alert>}
        <Field label={`Calendar ${term.toLowerCase()}`} hint={terms.length === 0 ? "No open terms: add one in the academic calendar." : undefined}>
          {(p) => <Select {...p} value={termId} onChange={(e) => setTermId(e.target.value)}><option value="">Choose…</option>
            {terms.map((t) => <option key={t.id} value={t.id}>{t.academicYearName} · {t.name} ({formatRange(t.startDate, t.endDate)})</option>)}</Select>}
        </Field>
        <Field label={`${labels.singular("COURSE")} ${term.toLowerCase()}`}>
          {(p) => <Select {...p} value={termNumber} onChange={(e) => { setTermNumber(e.target.value); setSubjectId(""); }}>
            {Array.from({ length: course.data?.effectiveTerms ?? 1 }, (_, i) => <option key={i + 1} value={i + 1}>{term} {i + 1}</option>)}</Select>}
        </Field>
        <div className="sm:col-span-2">
          <Field label={labels.singular("SUBJECT")} hint={curriculum.isSuccess && subjects.length === 0 ? `Nothing in the curriculum for ${term.toLowerCase()} ${termNumber}.` : undefined}>
            {(p) => <Select {...p} value={subjectId} onChange={(e) => setSubjectId(e.target.value)}><option value="">Choose…</option>
              {subjects.map((s) => <option key={s.id} value={s.subjectId}>{s.subjectCode} · {s.subjectName} ({s.credits} cr{s.elective ? ", elective" : ""})</option>)}</Select>}
          </Field>
        </div>
        <Field label="Section (optional)" hint={batch.section ? `Defaults to the batch's section ${batch.section}.` : undefined}>
          {(p) => <Input {...p} maxLength={20} value={section} onChange={(e) => setSection(e.target.value)} />}
        </Field>
        <Field label="Credits (optional)" hint="Defaults to the subject's credits.">
          {(p) => <Input {...p} inputMode="decimal" value={credits} onChange={(e) => setCredits(e.target.value.replace(/[^0-9.]/g, ""))} />}
        </Field>
      </div>
    </Modal>
  );
}
