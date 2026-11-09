import { unwrap, type ApprovedQuestion, type AssessmentDetail } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input, Modal, PageHeader, Select, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { TYPE_LABEL } from "../questions/QuestionShared";
import { GovernanceBadge, PURPOSE_LABEL, marks } from "./AssessmentShared";

type FixedRow = { questionVersionId: string; excerpt: string; type: string; marks: string; negativeMarks: string; defaultMarks: number; defaultNegative: number };
type RuleRow = { key: string; label: string; bankId: string; subjectId: string; difficultyId: string; questionType: string; count: string; marks: string; negativeMarks: string };

const num = (s: string) => (s.trim() === "" ? undefined : Number(s));
let seq = 0;
const newKey = () => `r${++seq}`;

/** Draft editor: settings, fixed questions from approved versions, random rules over pools, live totals. */
export function AssessmentEditorPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const q = useQuery({ queryKey: ["assessment", id], queryFn: async () => unwrap(await api.GET("/api/v1/assessments/{id}", { params: { path: { id } } })) });
  if (q.isError) return <><PageHeader title="Edit assessment" /><Alert tone="danger">{errorInfo(q.error).message}</Alert></>;
  if (!q.data) return <PageHeader title="Edit assessment" description="Loading…" />;
  if (!q.data.actions?.edit) return <><PageHeader title="Edit assessment" /><Alert tone="info">Only the author can edit a draft or a version in review. Published versions never change: create a new version instead.</Alert></>;
  return <Editor d={q.data} />;
}

function Editor({ d }: { d: AssessmentDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const v = d.current!;
  const [title, setTitle] = useState(v.title ?? "");
  const [description, setDescription] = useState(v.description ?? "");
  const [instructions, setInstructions] = useState(v.instructions ?? "");
  const [duration, setDuration] = useState(String(v.durationMinutes ?? 60));
  const [declared, setDeclared] = useState(v.declaredTotalMarks == null ? "" : String(v.declaredTotalMarks));
  const [negative, setNegative] = useState(v.negativeMarking ?? "QUESTION");
  const [shuffleQ, setShuffleQ] = useState(!!v.shuffleQuestions);
  const [shuffleO, setShuffleO] = useState(v.shuffleOptions !== false);
  const [fixed, setFixed] = useState<FixedRow[]>(() => (v.items ?? []).map((i) => ({
    questionVersionId: i.questionVersionId!, excerpt: i.excerpt ?? "", type: i.type ?? "", marks: String(i.marks ?? ""), negativeMarks: String(i.negativeMarks ?? ""),
    defaultMarks: i.marks ?? 1, defaultNegative: i.negativeMarks ?? 0 })));
  const [rules, setRules] = useState<RuleRow[]>(() => (v.rules ?? []).map((r) => ({
    key: newKey(), label: r.label ?? "", bankId: r.bankId ?? "", subjectId: r.subjectId ?? "", difficultyId: r.difficultyId ?? "", questionType: r.questionType ?? "",
    count: String(r.count ?? 1), marks: String(r.marks ?? 1), negativeMarks: r.negativeMarks ? String(r.negativeMarks) : "" })));
  const [picking, setPicking] = useState(false);
  const banks = useQuery({ queryKey: ["assessment-banks"], queryFn: async () => unwrap(await api.GET("/api/v1/assessment-sources/banks")) });
  const tax = useQuery({ queryKey: ["question-taxonomy"], staleTime: 60_000, retry: false, queryFn: async () => unwrap(await api.GET("/api/v1/question-taxonomy")) });
  const types = useQuery({ queryKey: ["question-types"], staleTime: 300_000, retry: false, queryFn: async () => unwrap(await api.GET("/api/v1/question-types")) });

  const total = useMemo(() => {
    const f = fixed.reduce((s, r) => s + (num(r.marks) ?? r.defaultMarks), 0);
    const rr = rules.reduce((s, r) => s + (num(r.count) ?? 0) * (num(r.marks) ?? 0), 0);
    return f + rr;
  }, [fixed, rules]);
  const count = fixed.length + rules.reduce((s, r) => s + (num(r.count) ?? 0), 0);

  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/assessments/{id}/draft", { params: { path: { id: d.id! } }, body: {
      title: title.trim(), description: description || undefined, instructions: instructions || undefined, durationMinutes: Number(duration),
      declaredTotalMarks: num(declared), negativeMarking: negative as "OFF", shuffleQuestions: shuffleQ, shuffleOptions: shuffleO,
      items: fixed.map((r) => ({ questionVersionId: r.questionVersionId, marks: num(r.marks), negativeMarks: negative === "OFF" ? undefined : num(r.negativeMarks) })),
      rules: rules.map((r) => ({ label: r.label || undefined, bankId: r.bankId, subjectId: r.subjectId || undefined, difficultyId: r.difficultyId || undefined,
        questionType: r.questionType || undefined, count: Number(r.count), marks: Number(r.marks), negativeMarks: negative === "OFF" ? undefined : num(r.negativeMarks) })),
      version: v.version!,
    } })),
    onSuccess: (x) => { qc.setQueryData(["assessment", d.id], x); void qc.invalidateQueries({ queryKey: ["assessment-validation", d.id] }); void qc.invalidateQueries({ queryKey: ["assessments"] }); void navigate(`/assessments/${d.id}`); },
  });

  const move = (i: number, by: number) => setFixed((rows) => { const r = [...rows]; const [x] = r.splice(i, 1); r.splice(i + by, 0, x!); return r; });
  const setRule = (key: string, patch: Partial<RuleRow>) => setRules((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  return (
    <>
      <PageHeader title={`Edit ${d.code}`} description={<span className="flex flex-wrap items-center gap-2">{PURPOSE_LABEL[d.purpose ?? ""]} · version {v.versionNo} <GovernanceBadge governance={v.governance} /></span>}
        actions={<div className="flex gap-2"><Button variant="secondary" onClick={() => void navigate(`/assessments/${d.id}`)}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={!title.trim()} onClick={() => save.mutate()}>Save draft</Button></div>} />
      <div className="space-y-4">
        {v.state === "IN_REVIEW" && <Alert tone="warning">This version is in review. Saving returns it to draft and makes existing reviews invalid.</Alert>}
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Card>
          <CardHeader title="Settings" />
          <CardBody>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Title">{(p) => <Input {...p} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}</Field>
              <Field label="Duration (minutes)">{(p) => <Input {...p} type="number" min={1} max={600} value={duration} onChange={(e) => setDuration(e.target.value)} />}</Field>
              <Field label="Declared total marks" hint="Optional. Validation checks it against the computed total.">{(p) => <Input {...p} type="number" min={0} step="0.5" value={declared} onChange={(e) => setDeclared(e.target.value)} />}</Field>
              <Field label="Negative marking">{(p) => <Select {...p} value={negative} onChange={(e) => setNegative(e.target.value)}>
                <option value="QUESTION">As set on each question</option><option value="CUSTOM">Set here for each question and rule</option><option value="OFF">Off</option></Select>}</Field>
            </div>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <Field label="Description">{(p) => <TextArea {...p} rows={2} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
              <Field label="Instructions for candidates">{(p) => <TextArea {...p} rows={2} maxLength={10000} value={instructions} onChange={(e) => setInstructions(e.target.value)} />}</Field>
            </div>
            <div className="mt-4 flex flex-wrap gap-6 text-sm">
              <label className="flex items-center gap-2"><input type="checkbox" checked={shuffleQ} onChange={(e) => setShuffleQ(e.target.checked)} />Shuffle question order for each candidate</label>
              <label className="flex items-center gap-2"><input type="checkbox" checked={shuffleO} onChange={(e) => setShuffleO(e.target.checked)} />Shuffle answer options (questions can opt out)</label>
            </div>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title={`Fixed questions (${fixed.length})`} description="Approved question versions every candidate gets. The exact version is kept even if the question changes later."
            actions={<Button variant="secondary" onClick={() => setPicking(true)}><Plus aria-hidden className="h-4 w-4" /> Add questions</Button>} />
          <CardBody>
            {fixed.length === 0 ? <p className="text-sm text-muted">No fixed questions.</p> : (
              <ol className="space-y-2">{fixed.map((r, i) => (
                <li key={r.questionVersionId} className="grid gap-2 rounded-lg border border-border p-3 sm:grid-cols-[1fr_auto]">
                  <div className="min-w-0 text-sm"><span className="font-medium">{i + 1}. </span>{r.excerpt || "(question)"}
                    <span className="ml-2 text-xs text-muted">{TYPE_LABEL[r.type] ?? r.type}</span></div>
                  <div className="flex flex-wrap items-end gap-2">
                    <Field label="Marks">{(p) => <Input {...p} className="w-20" type="number" min={0} step="0.5" placeholder={String(r.defaultMarks)} value={r.marks}
                      onChange={(e) => setFixed((rows) => rows.map((x) => (x === r ? { ...x, marks: e.target.value } : x)))} />}</Field>
                    {negative === "CUSTOM" && <Field label="Negative">{(p) => <Input {...p} className="w-20" type="number" min={0} step="0.25" value={r.negativeMarks}
                      onChange={(e) => setFixed((rows) => rows.map((x) => (x === r ? { ...x, negativeMarks: e.target.value } : x)))} />}</Field>}
                    <Button variant="ghost" aria-label={`Move question ${i + 1} up`} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp aria-hidden className="h-4 w-4" /></Button>
                    <Button variant="ghost" aria-label={`Move question ${i + 1} down`} disabled={i === fixed.length - 1} onClick={() => move(i, 1)}><ArrowDown aria-hidden className="h-4 w-4" /></Button>
                    <Button variant="ghost" aria-label={`Remove question ${i + 1}`} onClick={() => setFixed((rows) => rows.filter((x) => x !== r))}><Trash2 aria-hidden className="h-4 w-4" /></Button>
                  </div>
                </li>))}</ol>)}
          </CardBody>
        </Card>

        <Card>
          <CardHeader title={`Random rules (${rules.length})`} description="Each rule draws questions from approved questions matching its filters, differently for each candidate. Rules must not overlap."
            actions={<Button variant="secondary" disabled={!banks.data?.length} onClick={() => setRules((r) => [...r, { key: newKey(), label: "", bankId: banks.data?.[0]?.id ?? "", subjectId: "", difficultyId: "", questionType: "", count: "5", marks: "1", negativeMarks: "" }])}>
              <Plus aria-hidden className="h-4 w-4" /> Add rule</Button>} />
          <CardBody>
            {rules.length === 0 ? <p className="text-sm text-muted">No random rules.</p> : (
              <ul className="space-y-3">{rules.map((r, i) => (
                <li key={r.key} className="rounded-lg border border-border p-3">
                  <div className="grid gap-3 sm:grid-cols-3">
                    <Field label="Label">{(p) => <Input {...p} value={r.label} maxLength={150} placeholder={`Random rule ${i + 1}`} onChange={(e) => setRule(r.key, { label: e.target.value })} />}</Field>
                    <Field label="Bank">{(p) => <Select {...p} value={r.bankId} onChange={(e) => setRule(r.key, { bankId: e.target.value })}>
                      {(banks.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}</Field>
                    <Field label="Question type">{(p) => <Select {...p} value={r.questionType} onChange={(e) => setRule(r.key, { questionType: e.target.value })}>
                      <option value="">Any type</option>{(types.data ?? []).map((t) => <option key={t.code} value={t.code}>{TYPE_LABEL[t.code ?? ""] ?? t.code}</option>)}</Select>}</Field>
                    <Field label="Subject">{(p) => <Select {...p} value={r.subjectId} onChange={(e) => setRule(r.key, { subjectId: e.target.value })}>
                      <option value="">Any subject</option>{(tax.data?.subjects ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>}</Field>
                    <Field label="Difficulty">{(p) => <Select {...p} value={r.difficultyId} onChange={(e) => setRule(r.key, { difficultyId: e.target.value })}>
                      <option value="">Any difficulty</option>{(tax.data?.difficulties ?? []).map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</Select>}</Field>
                    <div className="grid grid-cols-3 gap-2">
                      <Field label="Draw">{(p) => <Input {...p} type="number" min={1} max={500} value={r.count} onChange={(e) => setRule(r.key, { count: e.target.value })} />}</Field>
                      <Field label="Marks">{(p) => <Input {...p} type="number" min={0} step="0.5" value={r.marks} onChange={(e) => setRule(r.key, { marks: e.target.value })} />}</Field>
                      {negative === "CUSTOM" && <Field label="Negative">{(p) => <Input {...p} type="number" min={0} step="0.25" value={r.negativeMarks} onChange={(e) => setRule(r.key, { negativeMarks: e.target.value })} />}</Field>}
                    </div>
                  </div>
                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                    <PoolSize rule={r} purpose={d.purpose ?? ""} />
                    <Button variant="ghost" onClick={() => setRules((rows) => rows.filter((x) => x.key !== r.key))}><Trash2 aria-hidden className="h-4 w-4" /> Remove rule</Button>
                  </div>
                </li>))}</ul>)}
          </CardBody>
        </Card>

        <Card><CardBody><p className="text-sm"><span className="font-medium">{count}</span> questions · <span className="font-medium">{marks(total)}</span> marks
          {declared && Number(declared) !== total && <Badge tone="warning" className="ml-2">Declared total {declared} differs</Badge>}</p></CardBody></Card>
      </div>
      {picking && <Picker existing={new Set(fixed.map((f) => f.questionVersionId))} onClose={() => setPicking(false)}
        onAdd={(qs) => setFixed((rows) => [...rows, ...qs.filter((x) => !rows.some((r) => r.questionVersionId === x.versionId)).map((x) => ({
          questionVersionId: x.versionId!, excerpt: x.excerpt ?? "", type: x.type ?? "", marks: "", negativeMarks: "", defaultMarks: x.marks ?? 1, defaultNegative: x.negativeMarks ?? 0 }))])} />}
    </>
  );
}

function PoolSize({ rule, purpose }: { rule: RuleRow; purpose: string }) {
  const api = useApi();
  const [debounced, setDebounced] = useState(rule);
  useEffect(() => { const t = setTimeout(() => setDebounced(rule), 300); return () => clearTimeout(t); }, [rule]);
  const q = useQuery({
    queryKey: ["pool-size", debounced.bankId, debounced.subjectId, debounced.difficultyId, debounced.questionType], enabled: !!debounced.bankId,
    queryFn: async () => unwrap(await api.GET("/api/v1/assessment-sources/banks/{bankId}/pool-size", { params: { path: { bankId: debounced.bankId },
      query: { subjectId: debounced.subjectId || undefined, difficultyId: debounced.difficultyId || undefined, type: debounced.questionType || undefined } } })),
  });
  const draw = Number(rule.count) || 0;
  if (!q.data) return <span className="text-sm text-muted">Counting matching questions…</span>;
  const size = q.data.size ?? 0;
  const tone = size < draw ? "danger" : size < 3 * draw ? (purpose === "ADMISSION" ? "danger" : "warning") : "success";
  const text = size < draw ? `Only ${size} approved questions match — fewer than the ${draw} drawn.`
    : size < 3 * draw ? `${size} approved questions match: fewer than three times the draw${purpose === "ADMISSION" ? " (admission requires at least 3×)" : ""}.`
      : `${size} approved questions match (fixed questions are excluded when drawing).`;
  return <Badge tone={tone}>{text}</Badge>;
}

function Picker({ existing, onClose, onAdd }: { existing: Set<string>; onClose: () => void; onAdd: (q: ApprovedQuestion[]) => void }) {
  const api = useApi();
  const banks = useQuery({ queryKey: ["assessment-banks"], queryFn: async () => unwrap(await api.GET("/api/v1/assessment-sources/banks")) });
  const [bank, setBank] = useState("");
  const [text, setText] = useState("");
  const [page, setPage] = useState(0);
  const [chosen, setChosen] = useState<Map<string, ApprovedQuestion>>(new Map());
  const bankId = bank || banks.data?.[0]?.id || "";
  const q = useQuery({
    queryKey: ["approved-questions", bankId, text, page], enabled: !!bankId,
    queryFn: async () => unwrap(await api.GET("/api/v1/assessment-sources/banks/{bankId}/questions", { params: { path: { bankId }, query: { q: text || undefined, page, size: 20 } } })),
  });
  const toggle = (x: ApprovedQuestion) => setChosen((m) => { const n = new Map(m); if (n.has(x.versionId!)) n.delete(x.versionId!); else n.set(x.versionId!, x); return n; });
  const pages = Math.max(1, Math.ceil((q.data?.total ?? 0) / 20));
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Add approved questions" description="Only current approved versions of active questions are listed. Answers are never shown here."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={chosen.size === 0} onClick={() => { onAdd([...chosen.values()]); onClose(); }}>Add {chosen.size || ""}</Button></>}>
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Bank">{(p) => <Select {...p} value={bankId} onChange={(e) => { setBank(e.target.value); setPage(0); }}>
            {(banks.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}</Field>
          <Field label="Search">{(p) => <Input {...p} type="search" value={text} onChange={(e) => { setText(e.target.value); setPage(0); }} />}</Field>
        </div>
        {q.isError && <Alert tone="danger">{errorInfo(q.error).message}</Alert>}
        <ul className="max-h-96 space-y-2 overflow-y-auto" aria-label="Approved questions">
          {(q.data?.items ?? []).map((x) => {
            const added = existing.has(x.versionId!);
            return (
              <li key={x.versionId}><label className="flex cursor-pointer gap-3 rounded-lg border border-border p-3 text-sm">
                <input type="checkbox" disabled={added} checked={added || chosen.has(x.versionId!)} onChange={() => toggle(x)} />
                <span className="min-w-0 flex-1"><span className="block">{x.excerpt || "(question)"}</span>
                  <span className="text-xs text-muted">{[TYPE_LABEL[x.type ?? ""] ?? x.type, x.subjectName, x.difficultyLabel, `${marks(x.marks)} marks`].filter(Boolean).join(" · ")}{added ? " · already added" : ""}</span></span>
              </label></li>);
          })}
          {q.data && (q.data.items ?? []).length === 0 && <li className="text-sm text-muted">No approved question matches.</li>}
        </ul>
        {pages > 1 && <div className="flex items-center justify-between text-sm"><Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
          <span>Page {page + 1} of {pages}</span><Button variant="ghost" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</Button></div>}
      </div>
    </Modal>
  );
}

export default AssessmentEditorPage;
