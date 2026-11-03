import { unwrap, type StudentNumberRule, type StudentOption } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, Field, Input, Modal, PageHeader, Select, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { renderTemplate } from "./studentModel";
import { useInvalidateStudents, useStudentOptions } from "./StudentShared";

const KIND_LABEL: Record<string, string> = { ADMISSION_TYPE: "Admission types", ADMISSION_CATEGORY: "Admission categories", DOCUMENT_CATEGORY: "Document categories" };

/** Student numbering rules (per university or college) and the configurable option lists. */
export function StudentSettingsPage() {
  const api = useApi();
  const { access } = useSession();
  const [editing, setEditing] = useState<StudentNumberRule | null>(null);
  const [adding, setAdding] = useState<string | null>(null);
  const rules = useQuery({ queryKey: ["students", "numbering-rules"], queryFn: async () => unwrap(await api.GET("/api/v1/student-settings/numbering-rules")) });
  const options = useStudentOptions();
  const canOptions = access.has("tenant.settings.manage");
  const columns: Column<StudentNumberRule>[] = [
    { key: "unit", header: "University / college", cell: (r) => <span className="block min-w-0"><span className="block truncate font-medium">{r.orgUnitName}</span>
      <span className="block text-xs text-muted">{r.configured ? "Own rule" : r.inheritedFrom ? "Inherited from the university" : "No rule (manual entry)"}</span></span> },
    { key: "mode", header: "Student IDs", cell: (r) => r.mode === "GENERATED" ? <span className="block min-w-0"><Badge tone="info">Generated</Badge>
      <code className="mt-1 block truncate text-xs">{r.template}</code></span> : <span className="block min-w-0"><Badge>Manual</Badge>
      <span className="mt-1 block truncate text-xs text-muted">{r.manualPattern ? `Format ${r.manualPattern}` : "Letters, digits and / - . _"}</span></span> },
    { key: "example", header: "Example", hideOnMobile: true, cell: (r) => <code className="text-xs">{r.mode === "GENERATED" ? r.example : r.manualPattern ?? "—"}</code> },
    { key: "registration", header: "Registration no.", hideOnMobile: true, cell: (r) => <code className="text-xs">{r.registrationPattern ?? "Any"}</code> },
    { key: "actions", header: "Actions", cell: (r) => r.canManage ? <Button size="sm" variant="ghost" aria-label={`Edit the rule of ${r.orgUnitName}`} onClick={() => setEditing(r)}><Pencil aria-hidden className="h-4 w-4" /></Button> : null },
  ];
  return (
    <>
      <PageHeader title="Student settings" description="How student IDs are numbered at each institution, and the admission and document options." />
      <div className="space-y-6">
        <Card>
          <CardHeader title="Student ID numbering" description="Each university or college decides its own format; a college without a rule follows its university." />
          <DataTable caption="Numbering rules" columns={columns} rows={rules.data} rowKey={(r) => r.orgUnitId!} loading={rules.isPending}
            error={rules.isError ? errorInfo(rules.error) : null} onRetry={() => void rules.refetch()} empty={{ title: "No universities or colleges visible" }} />
        </Card>
        {options.loading ? <Skeleton className="h-48 w-full" /> : (
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
            {Object.keys(KIND_LABEL).map((kind) => (
              <Card key={kind}>
                <CardHeader title={KIND_LABEL[kind]!} actions={canOptions ? <Button size="sm" variant="secondary" onClick={() => setAdding(kind)}><Plus aria-hidden className="h-4 w-4" /> Add</Button> : undefined} />
                <CardBody>
                  <ul className="space-y-2 text-sm">
                    {options.of(kind as StudentOption["kind"], true).map((o) => <OptionRow key={o.code} option={o} canEdit={canOptions} />)}
                  </ul>
                </CardBody>
              </Card>
            ))}
          </div>
        )}
      </div>
      {editing && <RuleDialog rule={editing} onClose={() => setEditing(null)} />}
      {adding && <OptionDialog kind={adding} onClose={() => setAdding(null)} />}
    </>
  );
}

function OptionRow({ option, canEdit }: { option: StudentOption; canEdit: boolean }) {
  const api = useApi();
  const invalidate = useInvalidateStudents();
  const toggle = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/student-settings/options/{kind}/{code}/status", { params: { path: { kind: option.kind!, code: option.code! } }, body: { active: !option.active } })),
    onSuccess: invalidate,
  });
  return (
    <li className="flex items-center justify-between gap-2">
      <span className={option.active ? "text-fg" : "text-muted line-through"}>{option.label} <code className="text-xs text-muted">{option.code}</code></span>
      {option.builtIn ? <Badge>Built in</Badge> : canEdit ? <Button size="sm" variant="ghost" loading={toggle.isPending} onClick={() => toggle.mutate()}>{option.active ? "Deactivate" : "Activate"}</Button> : null}
    </li>
  );
}

function RuleDialog({ rule, onClose }: { rule: StudentNumberRule; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const [mode, setMode] = useState(rule.mode ?? "MANUAL");
  const [template, setTemplate] = useState(rule.template ?? "{INST}{YYYY}{SEQ:4}");
  const [manual, setManual] = useState(rule.manualPattern ?? "");
  const [registration, setRegistration] = useState(rule.registrationPattern ?? "");
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/student-settings/numbering-rules/{orgUnitId}", { params: { path: { orgUnitId: rule.orgUnitId! } },
      body: { mode, ...(mode === "GENERATED" ? { template } : manual ? { manualPattern: manual } : {}), ...(registration ? { registrationPattern: registration } : {}),
        version: rule.configured ? rule.version! : -1 } })),
    onSuccess: () => { invalidate(); toast.show("success", "Numbering rule saved", rule.orgUnitName); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const preview = mode === "GENERATED" ? (() => { try { return renderTemplate(template, rule.orgUnitName?.split(" ").map((w) => w[0]).join("") ?? "INST", new Date().getFullYear(), 1); } catch { return ""; } })() : null;
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Student IDs at ${rule.orgUnitName}`} description="Existing student IDs never change."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={() => { setProblem(null); save.mutate(); }}>Save rule</Button></>}>
      <div className="space-y-4">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Field label="Numbering">{(f) => <Select {...f} value={mode} onChange={(e) => setMode(e.target.value as "MANUAL")}><option value="GENERATED">Generated from a template</option><option value="MANUAL">Entered manually</option></Select>}</Field>
        {mode === "GENERATED" ? (
          <Field label="Template" hint={<>Tokens: {"{INST}"} institution code, {"{YYYY}"} / {"{YY}"} admission year, one {"{SEQ:n}"} (3–8 digits). Preview: <code>{preview}</code></>}>
            {(f) => <Input {...f} value={template} maxLength={100} onChange={(e) => setTemplate(e.target.value)} />}
          </Field>
        ) : (
          <Field label="Format (optional)" hint="A = letter, 9 = digit, X = letter or digit, * = several letters or digits; other capitals, digits and / - . _ as written — e.g. MCC-9999">
            {(f) => <Input {...f} value={manual} maxLength={40} onChange={(e) => setManual(e.target.value)} />}
          </Field>
        )}
        <Field label="Registration number format (optional)" hint="Same notation, e.g. AAA/9999/*">{(f) => <Input {...f} value={registration} maxLength={40} onChange={(e) => setRegistration(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

function OptionDialog({ kind, onClose }: { kind: string; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/student-settings/options", { body: { kind: kind as "ADMISSION_TYPE", code: code.trim().toUpperCase(), label: label.trim() } })),
    onSuccess: () => { invalidate(); toast.show("success", "Option added"); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Add to ${KIND_LABEL[kind]?.toLowerCase()}`} description="Options can be deactivated later but never deleted, so records keep their meaning."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} disabled={!code.trim() || !label.trim()} onClick={() => { setProblem(null); save.mutate(); }}>Add</Button></>}>
      <div className="space-y-4">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Field label="Code" hint="Capital letters, digits and _, e.g. SPORTS_QUOTA">{(f) => <Input {...f} value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} />}</Field>
        <Field label="Label">{(f) => <Input {...f} value={label} maxLength={100} onChange={(e) => setLabel(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

export default StudentSettingsPage;
