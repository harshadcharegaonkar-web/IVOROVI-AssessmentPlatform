import { unwrap } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Select } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { QuestionTabs } from "./QuestionShared";

type Kind = "subjects" | "topics" | "learning-outcomes" | "difficulty-levels" | "attributes";

/** Subjects, topics and subtopics, learning outcomes, difficulty levels, tags and institution metadata fields. Never deleted, only deactivated. */
export function QuestionTaxonomyPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const manage = access.hasAnywhere("question.bank.manage");
  const tax = useQuery({ queryKey: ["question-taxonomy"], queryFn: async () => unwrap(await api.GET("/api/v1/question-taxonomy")) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["question-taxonomy"] });
  const [unit, setUnit] = useState("");
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [parentId, setParentId] = useState("");
  const [topicName, setTopicName] = useState("");
  const [loCode, setLoCode] = useState("");
  const [loText, setLoText] = useState("");
  const [bloom, setBloom] = useState("");
  const [attrKey, setAttrKey] = useState("");
  const [attrLabel, setAttrLabel] = useState("");
  const [attrType, setAttrType] = useState("TEXT");
  const [attrValues, setAttrValues] = useState("");
  const [attrRequired, setAttrRequired] = useState(false);
  const add = useMutation({
    mutationFn: async (kind: Kind) => {
      switch (kind) {
        case "subjects": return unwrap(await api.POST("/api/v1/question-taxonomy/subjects", { body: { orgUnitId: unit || undefined, code, name } }));
        case "topics": return unwrap(await api.POST("/api/v1/question-taxonomy/topics", { body: { subjectId, parentId: parentId || undefined, name: topicName } }));
        case "learning-outcomes": return unwrap(await api.POST("/api/v1/question-taxonomy/learning-outcomes", { body: { subjectId, code: loCode, description: loText, bloomLevel: bloom || undefined } }));
        case "attributes": return unwrap(await api.POST("/api/v1/question-taxonomy/attributes", { body: { key: attrKey, label: attrLabel, type: attrType as "TEXT",
          allowedValues: attrValues.split(",").map((s) => s.trim()).filter(Boolean), requiredForApproval: attrRequired } }));
        default: return null;
      }
    },
    onSuccess: () => { refresh(); setCode(""); setName(""); setTopicName(""); setLoCode(""); setLoText(""); setAttrKey(""); setAttrLabel(""); setAttrValues(""); },
  });
  const toggle = useMutation({
    mutationFn: async ({ kind, id, active }: { kind: Kind; id: string; active: boolean }) =>
      unwrap(await api.PUT("/api/v1/question-taxonomy/{kind}/{id}/status", { params: { path: { kind, id } }, body: { active } })),
    onSuccess: refresh,
  });
  const t = tax.data;
  const subjects = t?.subjects ?? [];
  const Status = ({ kind, id, status }: { kind: Kind; id: string; status?: string }) => (
    <span className="flex items-center gap-2">
      {status !== "ACTIVE" && <Badge tone="warning">Inactive</Badge>}
      {manage && <Button variant="ghost" size="sm" onClick={() => toggle.mutate({ kind, id, active: status !== "ACTIVE" })}>{status === "ACTIVE" ? "Deactivate" : "Reactivate"}</Button>}
    </span>
  );
  const err = add.error ?? toggle.error;
  return (
    <>
      <PageHeader title="Question bank" description="Classification used to organize and search questions. Entries in use are never deleted." />
      <QuestionTabs>
        {err && <Alert tone="danger">{errorInfo(err).message}</Alert>}
        <div className="grid gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader title="Subjects, topics and subtopics" />
            <CardBody className="space-y-4">
              <ul className="space-y-2 text-sm">{subjects.map((s) => (
                <li key={s.id}>
                  <div className="flex items-center justify-between gap-2"><span className="font-medium">{s.code} · {s.name}</span><Status kind="subjects" id={s.id!} status={s.status} /></div>
                  <ul className="ml-4 mt-1 space-y-1">{(t?.topics ?? []).filter((x) => x.subjectId === s.id && x.level === 1).map((x) => (
                    <li key={x.id}><div className="flex items-center justify-between gap-2"><span>{x.name}</span><Status kind="topics" id={x.id!} status={x.status} /></div>
                      <ul className="ml-4 text-muted">{(t?.topics ?? []).filter((y) => y.parentId === x.id).map((y) => (
                        <li key={y.id} className="flex items-center justify-between gap-2"><span>{y.name}</span><Status kind="topics" id={y.id!} status={y.status} /></li>))}</ul></li>))}</ul>
                </li>))}</ul>
              {manage && <>
                <div className="grid gap-2 sm:grid-cols-4">
                  <Select aria-label="Institution" value={unit} onChange={(e) => setUnit(e.target.value)}>{!access.has("question.bank.manage") && <option value="">Choose…</option>}<UnitOptions permission="question.bank.manage" /></Select>
                  <Input aria-label="Subject code" placeholder="Code" value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} />
                  <Input aria-label="Subject name" placeholder="Subject" value={name} maxLength={150} onChange={(e) => setName(e.target.value)} />
                  <Button variant="secondary" disabled={!code || !name} onClick={() => add.mutate("subjects")}>Add subject</Button>
                </div>
                <div className="grid gap-2 sm:grid-cols-4">
                  <Select aria-label="Subject for topic" value={subjectId} onChange={(e) => { setSubjectId(e.target.value); setParentId(""); }}>
                    <option value="">Subject…</option>{subjects.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>
                  <Select aria-label="Parent topic" value={parentId} onChange={(e) => setParentId(e.target.value)}><option value="">New topic</option>
                    {(t?.topics ?? []).filter((x) => x.subjectId === subjectId && x.level === 1).map((x) => <option key={x.id} value={x.id}>Subtopic of {x.name}</option>)}</Select>
                  <Input aria-label="Topic name" placeholder="Name" value={topicName} maxLength={150} onChange={(e) => setTopicName(e.target.value)} />
                  <Button variant="secondary" disabled={!subjectId || !topicName} onClick={() => add.mutate("topics")}>Add</Button>
                </div>
              </>}
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Learning outcomes" />
            <CardBody className="space-y-3">
              <ul className="space-y-1 text-sm">{(t?.outcomes ?? []).map((o) => (
                <li key={o.id} className="flex items-center justify-between gap-2"><span><span className="font-medium">{o.code}</span> {o.description}{o.bloomLevel && <Badge className="ml-1">{o.bloomLevel}</Badge>}</span>
                  <Status kind="learning-outcomes" id={o.id!} status={o.status} /></li>))}</ul>
              {manage && <div className="grid gap-2 sm:grid-cols-2">
                <Select aria-label="Subject for outcome" value={subjectId} onChange={(e) => setSubjectId(e.target.value)}><option value="">Subject…</option>
                  {subjects.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>
                <Select aria-label="Bloom level" value={bloom} onChange={(e) => setBloom(e.target.value)}><option value="">Bloom level (optional)</option>
                  {["REMEMBER", "UNDERSTAND", "APPLY", "ANALYZE", "EVALUATE", "CREATE"].map((b) => <option key={b} value={b}>{b}</option>)}</Select>
                <Input aria-label="Outcome code" placeholder="Code" value={loCode} maxLength={40} onChange={(e) => setLoCode(e.target.value)} />
                <Input aria-label="Outcome description" placeholder="Description" value={loText} maxLength={500} onChange={(e) => setLoText(e.target.value)} />
                <Button variant="secondary" disabled={!subjectId || !loCode || !loText} onClick={() => add.mutate("learning-outcomes")}>Add outcome</Button>
              </div>}
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Difficulty levels and tags" />
            <CardBody className="space-y-3 text-sm">
              <ul className="space-y-1">{(t?.difficulties ?? []).map((d) => <li key={d.id} className="flex items-center justify-between gap-2"><span>{d.rank}. {d.label}</span>
                <Status kind="difficulty-levels" id={d.id!} status={d.status} /></li>)}</ul>
              <p className="flex flex-wrap gap-1">{(t?.tags ?? []).map((g) => <Badge key={g.id}>{g.name}</Badge>)}{(t?.tags ?? []).length === 0 && <span className="text-muted">No tags yet (created on first use where the bank allows free tags).</span>}</p>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Institution metadata fields" description="Fields marked required must be filled before a question can be submitted." />
            <CardBody className="space-y-3">
              <ul className="space-y-1 text-sm">{(t?.attributes ?? []).map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-2"><span><code className="text-xs">{a.key}</code> {a.label} · {a.type}{a.requiredForApproval && <Badge tone="info" className="ml-1">Required</Badge>}</span>
                  <Status kind="attributes" id={a.id!} status={a.status} /></li>))}</ul>
              {manage && <div className="grid gap-2 sm:grid-cols-2">
                <Input aria-label="Field key" placeholder="key (e.g. syllabus_ref)" value={attrKey} maxLength={60} onChange={(e) => setAttrKey(e.target.value)} />
                <Input aria-label="Field label" placeholder="Label" value={attrLabel} maxLength={100} onChange={(e) => setAttrLabel(e.target.value)} />
                <Select aria-label="Field type" value={attrType} onChange={(e) => setAttrType(e.target.value)}>{["TEXT", "NUMBER", "BOOLEAN", "CHOICE", "DATE"].map((x) => <option key={x}>{x}</option>)}</Select>
                {attrType === "CHOICE" && <Input aria-label="Allowed values" placeholder="Comma-separated values" value={attrValues} onChange={(e) => setAttrValues(e.target.value)} />}
                <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={attrRequired} onChange={(e) => setAttrRequired(e.target.checked)} />Required for submission</label>
                <Button variant="secondary" disabled={!attrKey || !attrLabel} onClick={() => add.mutate("attributes")}>Add field</Button>
              </div>}
              {manage && <Field label="Note">{() => <p className="text-xs text-muted">Keys use lower-case letters, digits and underscores.</p>}</Field>}
            </CardBody>
          </Card>
        </div>
      </QuestionTabs>
    </>
  );
}

export default QuestionTaxonomyPage;
