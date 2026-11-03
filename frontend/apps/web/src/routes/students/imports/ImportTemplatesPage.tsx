import { unwrap, type StudentImportSavedMapping, type StudentImportSettings } from "@ivorovi/api-client";
import { Badge, Breadcrumbs, Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, Field, PageHeader, Select, Switch, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Download, Trash2 } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime } from "../../../lib/format";
import { useOrgLabels } from "../../../session/useOrgLabels";
import { UnitChoices } from "../../academics/AcademicsShared";
import { useDownload, useRefreshImports } from "./ImportShared";

const SECTIONS = [
  { key: "PERSONAL", label: "Student personal information" },
  { key: "IDENTIFICATION", label: "Student identification" },
  { key: "ADMISSION", label: "Admission details" },
  { key: "ENROLMENT", label: "Academic enrolment" },
  { key: "GUARDIAN", label: "Guardian information" },
] as const;

/** Import template management: standard templates by section, saved column mappings, duplicate-matching rules. */
export function ImportTemplatesPage() {
  return (
    <>
      <Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Imports", to: "/students/imports" }, { label: "Templates and rules" }]} />
      <PageHeader title="Import templates and rules" description="Standard templates, your saved column mappings and how possible duplicates are detected." />
      <div className="space-y-6">
        <StandardTemplates />
        <SavedMappings />
        <MatchingRules />
      </div>
    </>
  );
}

function StandardTemplates() {
  const api = useApi();
  const labels = useOrgLabels();
  const download = useDownload();
  const [institutionId, setInstitutionId] = useState("");
  const [sections, setSections] = useState<string[]>(SECTIONS.map((s) => s.key));
  const get = (format: "XLSX" | "CSV") => download(() => api.GET("/api/v1/student-imports/template", { params: { query: {
    format, sections: sections as never, ...(institutionId ? { institutionId } : {}) } }, parseAs: "blob" }), `ivorovi-student-import-template.${format === "CSV" ? "csv" : "xlsx"}`);
  return (
    <Card>
      <CardHeader title="Standard templates" description="Choose the sections to include. Excel templates add an instructions sheet and, for an institution, its program, course, batch and academic-year codes." />
      <CardBody className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <fieldset>
          <legend className="mb-3 text-sm font-medium text-fg">Sections</legend>
          <div className="space-y-2">
            {SECTIONS.map((s) => (
              <label key={s.key} className="flex items-center gap-3 text-sm">
                <input type="checkbox" className="h-4 w-4 accent-[var(--color-primary)]" checked={sections.includes(s.key)}
                  onChange={(e) => setSections((x) => e.target.checked ? [...x, s.key] : x.filter((k) => k !== s.key))} />
                {s.label}{s.key === "PERSONAL" && <span className="text-xs text-muted">(first name is always included)</span>}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="space-y-4">
          <Field label={`Reference codes of (optional)`} hint={`Adds the codes of a ${labels.singular("UNIVERSITY").toLowerCase()} or ${labels.singular("COLLEGE").toLowerCase()} to the Excel template.`}>
            {(f) => <Select {...f} value={institutionId} onChange={(e) => setInstitutionId(e.target.value)}><option value="">No reference codes</option>
              <UnitChoices permission="student.import" types={["UNIVERSITY", "COLLEGE"]} /></Select>}
          </Field>
          <div className="flex flex-wrap gap-3">
            <Button variant="primary" disabled={sections.length === 0} onClick={() => void get("XLSX")}><Download aria-hidden className="h-4 w-4" /> Excel template</Button>
            <Button disabled={sections.length === 0} onClick={() => void get("CSV")}><Download aria-hidden className="h-4 w-4" /> CSV template</Button>
          </div>
        </div>
      </CardBody>
    </Card>
  );
}

function SavedMappings() {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const [removing, setRemoving] = useState<StudentImportSavedMapping | null>(null);
  const q = useQuery({ queryKey: ["student-imports", "mappings", "all"], queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/mappings")) });
  const remove = useMutation({
    mutationFn: async (id: string) => unwrap(await api.DELETE("/api/v1/student-imports/mappings/{mappingId}", { params: { path: { mappingId: id } } })),
    onSuccess: () => { refresh(); setRemoving(null); toast.show("success", "Mapping deleted"); },
    onError: (e) => toast.show("danger", "Not deleted", errorInfo(e).message),
  });
  const columns: Column<StudentImportSavedMapping>[] = [
    { key: "name", header: "Mapping", cell: (m) => <span className="block min-w-0"><span className="block truncate font-medium">{m.name}</span>
      <span className="block truncate text-xs text-muted">{m.columns?.length ?? 0} columns: {(m.columns ?? []).slice(0, 4).map((c) => c.header).join(", ")}{(m.columns?.length ?? 0) > 4 ? "…" : ""}</span></span> },
    { key: "scope", header: "Used for", hideOnMobile: true, cell: (m) => m.institutionName ?? <Badge tone="info">Whole organization</Badge> },
    { key: "updated", header: "Updated", hideOnMobile: true, cell: (m) => <span className="block min-w-0"><span className="block">{formatDateTime(m.updatedAt)}</span><span className="block truncate text-xs text-muted">{m.updatedByName}</span></span> },
    { key: "actions", header: "Actions", cell: (m) => <Button size="sm" variant="ghost" aria-label={`Delete ${m.name}`} onClick={() => setRemoving(m)}><Trash2 aria-hidden className="h-4 w-4" /></Button> },
  ];
  return (
    <Card>
      <CardHeader title="Saved column mappings" description="Saved from the mapping step. Choose one when uploading a file with the same column headings." />
      <DataTable caption="Saved column mappings" columns={columns} rows={q.data} rowKey={(m) => m.id!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        empty={{ title: "No saved mappings", description: "Tick 'Save as a reusable mapping' in the mapping step of an import." }} />
      <ConfirmDialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)} title={`Delete ${removing?.name ?? "mapping"}?`} tone="danger" confirmLabel="Delete"
        description="Imports that used it keep their mapping." busy={remove.isPending} onConfirm={() => removing && remove.mutate(removing.id!)} />
    </Card>
  );
}

function MatchingRules() {
  const api = useApi();
  const q = useQuery({ queryKey: ["student-imports", "settings"], queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/settings")) });
  return (
    <Card>
      <CardHeader title="Duplicate-matching rules" description="Student IDs, registration numbers, admission numbers and institutional emails always identify a student. These rules add checks for possible duplicates; matches always need a decision." />
      {q.isPending ? <CardBody><span className="text-sm text-muted">Loading…</span></CardBody> : (
        <ul className="divide-y divide-border">
          {(q.data ?? []).map((s) => <RuleRow key={`${s.institutionId}-${s.version}`} rule={s} />)}
          {(q.data ?? []).length === 0 && <li className="p-6 text-sm text-muted">No institutions you can import into.</li>}
        </ul>
      )}
    </Card>
  );
}

function RuleRow({ rule }: { rule: StudentImportSettings }) {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const [v, setV] = useState(rule);
  const dirty = JSON.stringify(v) !== JSON.stringify(rule);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/student-imports/settings/{institutionId}", { params: { path: { institutionId: rule.institutionId! } }, body: {
      matchPersonalEmail: !!v.matchPersonalEmail, matchInstitutionalEmail: !!v.matchInstitutionalEmail, matchNameDob: !!v.matchNameDob,
      matchNameMobile: !!v.matchNameMobile, requireDateOfBirth: !!v.requireDateOfBirth, ...(rule.configured ? { version: rule.version } : {}) } })),
    onSuccess: () => { refresh(); toast.show("success", "Rules saved", rule.institutionName); },
    onError: (e) => toast.show("danger", "Not saved", errorInfo(e).message),
  });
  const toggles: { key: keyof StudentImportSettings; label: string }[] = [
    { key: "matchPersonalEmail", label: "Same personal email" },
    { key: "matchNameDob", label: "Same name and date of birth" },
    { key: "matchNameMobile", label: "Same name and mobile" },
    { key: "matchInstitutionalEmail", label: "Institutional email (within the file)" },
    { key: "requireDateOfBirth", label: "Require date of birth" },
  ];
  return (
    <li className="space-y-3 px-5 py-4 sm:px-6">
      <p className="flex flex-wrap items-center gap-2 font-medium text-fg">{rule.institutionName}{!rule.configured && <Badge>Defaults</Badge>}</p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {toggles.map((t) => {
          const id = `${rule.institutionId}-${t.key}`;
          return (
            <span key={t.key} className="flex items-center gap-2 text-sm">
              <Switch id={id} checked={!!v[t.key]} onCheckedChange={(c) => setV((x) => ({ ...x, [t.key]: c }))} label={`${t.label} for ${rule.institutionName}`} />
              <label htmlFor={id}>{t.label}</label>
            </span>
          );
        })}
      </div>
      {dirty && <div className="flex gap-2"><Button size="sm" variant="primary" loading={save.isPending} onClick={() => save.mutate()}>Save</Button>
        <Button size="sm" variant="ghost" onClick={() => setV(rule)}>Discard</Button></div>}
    </li>
  );
}

export default ImportTemplatesPage;
