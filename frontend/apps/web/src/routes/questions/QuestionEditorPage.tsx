import { unwrap, type QuestionVersion } from "@ivorovi/api-client";
import { CONTENT_FORMAT, fromSource, texProblem, toSource, type Block, type ContentDoc } from "@ivorovi/content";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, Input, PageHeader, Select, Skeleton, Switch, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { Content, TYPE_LABEL } from "./QuestionShared";

type ImageBlock = Extract<Block, { type: "image" }>;
interface Option { id: string; source: string }
const LETTERS = "ABCDEFGHIJ";

/** Problems in the formulas of a source text (same rules as the server). */
function mathProblems(source: string): string[] {
  const out: string[] = [];
  for (const b of fromSource(source).blocks) {
    const texes = b.type === "math" ? [b.tex] : b.type === "paragraph" ? b.runs.flatMap((r) => ("math" in r ? [r.math] : [])) : [];
    for (const t of texes) { const p = texProblem(t); if (p) out.push(`${t.slice(0, 40)} — ${p}`); }
  }
  return out;
}

function withImages(source: string, images: ImageBlock[]): ContentDoc {
  const doc = fromSource(source);
  return { format: CONTENT_FORMAT, blocks: [...doc.blocks, ...images] };
}

/** Create a draft (bank route) or edit the open version (question route). Drafts are always author-owned. */
export function QuestionEditorPage() {
  const { bankId: bankParam, id } = useParams();
  const api = useApi();
  const detail = useQuery({ queryKey: ["question", id], enabled: !!id, queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}", { params: { path: { id: id! } } })) });
  const current = detail.data?.current;
  const key = useQuery({ queryKey: ["question-key", id, current?.versionNo], enabled: !!current?.keyVisible,
    queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}/versions/{no}/answer-key", { params: { path: { id: id!, no: current!.versionNo! } } })) });
  if (id && (detail.isPending || (current?.keyVisible && key.isPending))) return <Skeleton className="h-96 w-full" />;
  if (detail.isError) return <ErrorState message={errorInfo(detail.error).message} />;
  return <Editor bankId={bankParam ?? detail.data?.bankId ?? ""} questionId={id} version={current}
    answerKey={key.data?.key as Record<string, unknown> | undefined} explanation={key.data?.explanation} />;
}

function Editor({ bankId, questionId, version, answerKey, explanation: initialExplanation }: {
  bankId: string; questionId?: string | undefined; version?: QuestionVersion | undefined; answerKey?: Record<string, unknown> | undefined; explanation?: string | undefined;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const tax = useQuery({ queryKey: ["question-taxonomy"], staleTime: 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/question-taxonomy")) });
  const assets = useQuery({ queryKey: ["question-assets", bankId], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{bankId}/assets", { params: { path: { bankId } } })) });
  const initial = toSource(version?.content as ContentDoc | undefined);
  const body = (version?.body ?? {}) as Record<string, unknown>;
  const [type, setType] = useState(version?.type ?? "SINGLE_CHOICE");
  const [source, setSource] = useState(initial.source);
  const [images, setImages] = useState<ImageBlock[]>(initial.images);
  const [options, setOptions] = useState<Option[]>(((body.options as { id: string; content: ContentDoc }[] | undefined) ?? [{ id: "A", content: fromSource("") }, { id: "B", content: fromSource("") },
    { id: "C", content: fromSource("") }, { id: "D", content: fromSource("") }]).map((o) => ({ id: o.id, source: toSource(o.content).source })));
  const [correct, setCorrect] = useState<string[]>(() => {
    const c = answerKey?.correct;
    return Array.isArray(c) ? c.map(String) : c ? [String(c)] : [];
  });
  const [tf, setTf] = useState(answerKey?.value === false ? "false" : "true");
  const [answers, setAnswers] = useState(((answerKey?.answers as { text: string }[] | undefined) ?? []).map((a) => a.text).join("\n"));
  const [match, setMatch] = useState(String(answerKey?.match ?? "NORMALIZED"));
  const [value, setValue] = useState(String(answerKey?.value ?? ""));
  const tol = (answerKey?.tolerance ?? {}) as { type?: string; value?: string };
  const [tolType, setTolType] = useState(tol.type ?? "ABSOLUTE");
  const [tolValue, setTolValue] = useState(tol.value ?? "0");
  const [precision, setPrecision] = useState(String(body.precision ?? 2));
  const [unit, setUnit] = useState(String(body.unit ?? ""));
  const [rounding, setRounding] = useState(String(answerKey?.rounding ?? "ROUND_HALF_UP"));
  const partial = answerKey?.partial as { tolerance?: { value?: string }; percent?: number } | undefined;
  const [partialTol, setPartialTol] = useState(partial?.tolerance?.value ?? "");
  const [partialPct, setPartialPct] = useState(String(partial?.percent ?? 50));
  const [mode, setMode] = useState(String((version?.marking as Record<string, unknown> | undefined)?.mode ?? "ALL_OR_NOTHING"));
  const [marks, setMarks] = useState(String(version?.marks ?? "1"));
  const [negative, setNegative] = useState(String(version?.negativeMarks ?? "0"));
  const [explanation, setExplanation] = useState(initialExplanation ?? "");
  const [subjectId, setSubjectId] = useState(version?.subjectId ?? "");
  const [topicId, setTopicId] = useState(version?.topicId ?? "");
  const [subtopicId, setSubtopicId] = useState(version?.subtopicId ?? "");
  const [difficultyId, setDifficultyId] = useState(version?.difficultyId ?? "");
  const [tags, setTags] = useState((version?.tags ?? []).join(", "));
  const [imageId, setImageId] = useState("");
  const [imageAlt, setImageAlt] = useState("");
  const [imageDecorative, setImageDecorative] = useState(false);

  const choice = type === "SINGLE_CHOICE" || type === "MULTIPLE_CHOICE";
  const doc = useMemo(() => withImages(source, images), [source, images]);
  const problems = [...mathProblems(source), ...options.flatMap((o) => (choice ? mathProblems(o.source) : []))];

  const draft = () => {
    const optionBody = options.filter((o) => o.source.trim()).map((o) => ({ id: o.id, content: fromSource(o.source) }));
    let b: Record<string, unknown> = {};
    let k: Record<string, unknown>;
    let m: Record<string, unknown> = {};
    if (choice) {
      b = { options: optionBody };
      k = type === "SINGLE_CHOICE" ? { correct: correct[0] ?? "" } : { correct };
      if (type === "MULTIPLE_CHOICE") m = { mode };
    } else if (type === "TRUE_FALSE") k = { value: tf === "true" };
    else if (type === "SHORT_ANSWER") k = { answers: answers.split("\n").map((t) => t.trim()).filter(Boolean).map((text) => ({ text })), match };
    else {
      b = { precision: Number(precision), ...(unit.trim() ? { unit: unit.trim() } : {}) };
      k = { value, tolerance: { type: tolType, value: tolValue || "0" }, rounding,
        ...(partialTol ? { partial: { tolerance: { type: tolType, value: partialTol }, percent: Number(partialPct) } } : {}) };
    }
    return { type, content: doc as unknown as Record<string, never>, body: b as Record<string, never>, marking: m as Record<string, never>, answerKey: k as Record<string, never>,
      explanation: explanation || undefined, marks: Number(marks), negativeMarks: Number(negative), subjectId: subjectId || undefined, topicId: topicId || undefined,
      subtopicId: subtopicId || undefined, difficultyId: difficultyId || undefined,
      tags: tags.split(",").map((t) => t.trim()).filter(Boolean), ...(version ? { version: version.version } : {}) };
  };
  const save = useMutation({
    mutationFn: async () => questionId
      ? unwrap(await api.PUT("/api/v1/questions/{id}", { params: { path: { id: questionId } }, body: draft() }))
      : unwrap(await api.POST("/api/v1/question-banks/{bankId}/questions", { params: { path: { bankId } }, body: draft() })),
    onSuccess: (d) => { void qc.invalidateQueries({ queryKey: ["questions"] }); void qc.invalidateQueries({ queryKey: ["question"] }); void navigate(`/questions/items/${d.id}`); },
  });
  const usable = (assets.data ?? []).filter((a) => a.scanStatus === "CLEAN" || a.scanStatus === "NOT_SCANNED_DEV");
  const topics = (tax.data?.topics ?? []).filter((t) => t.subjectId === subjectId && t.level === 1 && t.status === "ACTIVE");
  const subtopics = (tax.data?.topics ?? []).filter((t) => t.parentId === topicId && t.level === 2 && t.status === "ACTIVE");
  const inReview = version && version.state !== "DRAFT";

  return (
    <>
      <PageHeader title={questionId ? `Edit version ${version?.versionNo}` : "New question"} description="Write formulas between $ signs (inline) or $$ (own line). Formulas are checked and rendered safely; HTML is never interpreted." />
      {inReview && <Alert tone="warning" title="This version is in review">Saving changes returns it to draft and invalidates its reviews; it must be submitted and reviewed again.</Alert>}
      {save.isError && <Alert tone="danger" title="Not saved">{errorInfo(save.error).message}</Alert>}
      <div className="mt-4 grid gap-6 xl:grid-cols-2">
        <div className="space-y-6">
          <Card>
            <CardHeader title="Question" />
            <CardBody className="space-y-4">
              <Field label="Type">{(p) => <Select {...p} value={type} disabled={!!questionId} onChange={(e) => setType(e.target.value)}>
                {Object.entries(TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}</Field>
              <Field label="Statement" hint="Blank line = new paragraph. Example: Evaluate $\int_0^1 x^2\,dx$.">{(p) =>
                <TextArea {...p} rows={6} value={source} maxLength={20000} onChange={(e) => setSource(e.target.value)} className="font-mono text-sm" />}</Field>
              {problems.length > 0 && <Alert tone="danger" title="Formula problems">{problems.map((x) => <div key={x}>{x}</div>)}</Alert>}
              <fieldset className="space-y-2">
                <legend className="text-sm font-medium">Images</legend>
                {images.map((im, i) => (
                  <div key={i} className="flex items-center gap-2 text-sm">
                    <span className="flex-1 truncate">{im.decorative ? "Decorative image" : im.alt}</span>
                    <Button variant="ghost" size="sm" aria-label="Remove image" onClick={() => setImages(images.filter((_, j) => j !== i))}><Trash2 aria-hidden className="h-4 w-4" /></Button>
                  </div>))}
                <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                  <Select aria-label="Image" value={imageId} onChange={(e) => { setImageId(e.target.value); setImageAlt(usable.find((a) => a.id === e.target.value)?.altText ?? ""); }}>
                    <option value="">Choose a scanned image…</option>{usable.map((a) => <option key={a.id} value={a.id}>{a.fileName} ({a.width}×{a.height})</option>)}</Select>
                  <Input aria-label="Alternative text" placeholder="Describe the image" value={imageAlt} maxLength={250} disabled={imageDecorative} onChange={(e) => setImageAlt(e.target.value)} />
                  <Button variant="secondary" disabled={!imageId || (!imageDecorative && !imageAlt.trim())}
                    onClick={() => { setImages([...images, imageDecorative ? { type: "image", asset: imageId, decorative: true } : { type: "image", asset: imageId, alt: imageAlt.trim() }]); setImageId(""); setImageAlt(""); }}>
                    <Plus aria-hidden className="h-4 w-4" /> Add</Button>
                </div>
                <Switch label="Decorative (no description needed)" checked={imageDecorative} onCheckedChange={setImageDecorative} />
              </fieldset>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Answer" description="The answer key is stored encrypted and shown only to permitted staff; it is never part of question or search responses." />
            <CardBody className="space-y-4">
              {choice && (
                <fieldset className="space-y-2">
                  <legend className="text-sm font-medium">Options — mark the correct {type === "SINGLE_CHOICE" ? "one" : "ones"}</legend>
                  {options.map((o, i) => (
                    <div key={o.id} className="flex items-start gap-2">
                      <input type={type === "SINGLE_CHOICE" ? "radio" : "checkbox"} name="correct" aria-label={`Option ${o.id} is correct`} className="mt-3"
                        checked={correct.includes(o.id)} onChange={(e) => setCorrect(type === "SINGLE_CHOICE" ? [o.id] : e.target.checked ? [...correct, o.id] : correct.filter((c) => c !== o.id))} />
                      <span className="mt-2 w-5 font-semibold">{o.id}</span>
                      <Input aria-label={`Option ${o.id}`} value={o.source} maxLength={2000} onChange={(e) => setOptions(options.map((x, j) => (j === i ? { ...x, source: e.target.value } : x)))} />
                      {options.length > 2 && <Button variant="ghost" size="sm" aria-label={`Remove option ${o.id}`}
                        onClick={() => { const rest = options.filter((_, j) => j !== i).map((x, j) => ({ ...x, id: LETTERS[j]! })); setOptions(rest); setCorrect([]); }}><Trash2 aria-hidden className="h-4 w-4" /></Button>}
                    </div>))}
                  {options.length < 10 && <Button variant="secondary" size="sm" onClick={() => setOptions([...options, { id: LETTERS[options.length]!, source: "" }])}><Plus aria-hidden className="h-4 w-4" /> Option</Button>}
                  {type === "MULTIPLE_CHOICE" && <Field label="Scoring">{(p) => <Select {...p} value={mode} onChange={(e) => setMode(e.target.value)}>
                    <option value="ALL_OR_NOTHING">All or nothing</option><option value="PARTIAL">Partial credit</option></Select>}</Field>}
                </fieldset>)}
              {type === "TRUE_FALSE" && <Field label="Correct answer">{(p) => <Select {...p} value={tf} onChange={(e) => setTf(e.target.value)}><option value="true">True</option><option value="false">False</option></Select>}</Field>}
              {type === "SHORT_ANSWER" && <>
                <Field label="Accepted answers" hint="One per line.">{(p) => <TextArea {...p} rows={3} value={answers} onChange={(e) => setAnswers(e.target.value)} />}</Field>
                <Field label="Matching">{(p) => <Select {...p} value={match} onChange={(e) => setMatch(e.target.value)}><option value="NORMALIZED">Ignore case, spacing and accents</option>
                  <option value="CASE_INSENSITIVE">Ignore case</option><option value="EXACT">Exact</option></Select>}</Field>
              </>}
              {type === "NUMERIC" && <div className="grid gap-3 sm:grid-cols-3">
                <Field label="Correct value" hint="e.g. -12.5 or 6.02e23">{(p) => <Input {...p} inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />}</Field>
                <Field label="Tolerance type">{(p) => <Select {...p} value={tolType} onChange={(e) => setTolType(e.target.value)}><option value="ABSOLUTE">Absolute (±)</option><option value="PERCENT">Percent (±%)</option></Select>}</Field>
                <Field label="Tolerance">{(p) => <Input {...p} inputMode="decimal" value={tolValue} onChange={(e) => setTolValue(e.target.value)} />}</Field>
                <Field label="Decimal places">{(p) => <Input {...p} type="number" min={0} max={10} value={precision} onChange={(e) => setPrecision(e.target.value)} />}</Field>
                <Field label="Extra decimals">{(p) => <Select {...p} value={rounding} onChange={(e) => setRounding(e.target.value)}><option value="ROUND_HALF_UP">Round half up</option><option value="REJECT_EXCESS_PRECISION">Mark wrong</option></Select>}</Field>
                <Field label="Unit (shown)">{(p) => <Input {...p} value={unit} maxLength={20} onChange={(e) => setUnit(e.target.value)} />}</Field>
                <Field label="Partial-marks tolerance" hint="Optional, wider band">{(p) => <Input {...p} inputMode="decimal" value={partialTol} onChange={(e) => setPartialTol(e.target.value)} />}</Field>
                <Field label="Partial marks (%)">{(p) => <Input {...p} type="number" min={1} max={99} value={partialPct} onChange={(e) => setPartialPct(e.target.value)} />}</Field>
              </div>}
              <Field label="Explanation (optional)">{(p) => <TextArea {...p} rows={2} maxLength={5000} value={explanation} onChange={(e) => setExplanation(e.target.value)} />}</Field>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Marks and classification" />
            <CardBody className="grid gap-3 sm:grid-cols-2">
              <Field label="Marks">{(p) => <Input {...p} inputMode="decimal" value={marks} onChange={(e) => setMarks(e.target.value)} />}</Field>
              <Field label="Negative marks">{(p) => <Input {...p} inputMode="decimal" value={negative} onChange={(e) => setNegative(e.target.value)} />}</Field>
              <Field label="Subject">{(p) => <Select {...p} value={subjectId} onChange={(e) => { setSubjectId(e.target.value); setTopicId(""); setSubtopicId(""); }}><option value="">None</option>
                {(tax.data?.subjects ?? []).filter((s) => s.status === "ACTIVE").map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>}</Field>
              <Field label="Topic">{(p) => <Select {...p} value={topicId} disabled={!subjectId} onChange={(e) => { setTopicId(e.target.value); setSubtopicId(""); }}><option value="">None</option>
                {topics.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</Select>}</Field>
              <Field label="Subtopic">{(p) => <Select {...p} value={subtopicId} disabled={!topicId} onChange={(e) => setSubtopicId(e.target.value)}><option value="">None</option>
                {subtopics.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</Select>}</Field>
              <Field label="Difficulty">{(p) => <Select {...p} value={difficultyId} onChange={(e) => setDifficultyId(e.target.value)}><option value="">None</option>
                {(tax.data?.difficulties ?? []).filter((d) => d.status === "ACTIVE").map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}</Select>}</Field>
              <div className="sm:col-span-2"><Field label="Tags" hint="Comma-separated">{(p) => <Input {...p} value={tags} onChange={(e) => setTags(e.target.value)} />}</Field></div>
            </CardBody>
          </Card>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => void navigate(-1)}>Cancel</Button>
            <Button variant="primary" loading={save.isPending} disabled={!source.trim() || problems.length > 0} onClick={() => save.mutate()}>Save draft</Button>
          </div>
        </div>
        <Card className="xl:sticky xl:top-4 xl:self-start">
          <CardHeader title="Preview" description="As candidates will see the statement and options (no answer information)." />
          <CardBody>
            <div data-testid="question-preview">
              <Content doc={doc} />
              {choice && <ol className="mt-3 space-y-2">{options.filter((o) => o.source.trim()).map((o) => (
                <li key={o.id} className="flex gap-3 rounded-lg border border-border p-3"><span className="font-semibold">{o.id}.</span><Content doc={fromSource(o.source)} className="min-w-0 flex-1" /></li>))}</ol>}
              {type === "NUMERIC" && <p className="mt-3 text-sm text-muted">Numeric answer{unit ? ` in ${unit}` : ""}, {precision} decimal place(s).</p>}
            </div>
          </CardBody>
        </Card>
      </div>
    </>
  );
}

export default QuestionEditorPage;
