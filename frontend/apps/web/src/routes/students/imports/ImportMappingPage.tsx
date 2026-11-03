import { unwrap, type StudentImportField, type StudentImportJob } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input, Select, Skeleton, Switch } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Circle, Save } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { mappingProblems } from "./importModel";
import { ImportJobFrame, stepPath, useRefreshImports } from "./ImportShared";

/** Column mapping: automatic matches, manual choices, required fields, unmapped warnings and a preview of mapped values. */
export function ImportMappingPage() {
  return (
    <ImportJobFrame step="mapping" title="Map columns" description="Match every column of the file to an Ivorovi field. Ambiguous columns are never mapped automatically.">
      {(job) => <MappingEditor key={job.version} job={job} />}
    </ImportJobFrame>
  );
}

function MappingEditor({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const navigate = useNavigate();
  const refresh = useRefreshImports();
  const editable = !!job.can?.map;
  const fields = useQuery({ queryKey: ["student-imports", "fields", job.institutionId], staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/fields", { params: { query: { institutionId: job.institutionId } } })) });
  const sample = useQuery({ queryKey: ["student-imports", "sample", job.id], staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/{id}/sample", { params: { path: { id: job.id! }, query: { rows: 5 } } })) });
  const initial = useMemo(() => Object.fromEntries((job.mapping ?? []).map((m) => [m.column!, m.field ?? ""])) as Record<number, string>, [job.mapping]);
  const [mapping, setMapping] = useState<Record<number, string>>(initial);
  const [save, setSave] = useState(false);
  const [name, setName] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const list = fields.data ?? [];
  const byKey = new Map(list.map((f) => [f.key!, f]));
  const personalAllowed = !!job.can?.viewValues;
  const problems = mappingProblems(mapping, list.map((f) => ({ key: f.key!, label: f.label!, section: f.section!, required: !!f.required, personal: !!f.personal })), personalAllowed);
  const usedCount = new Map<string, number>();
  Object.values(mapping).filter(Boolean).forEach((f) => usedCount.set(f, (usedCount.get(f) ?? 0) + 1));
  const unmapped = (job.headers ?? []).filter((_, i) => !mapping[i]);
  const submit = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/student-imports/{id}/mapping", { params: { path: { id: job.id! } }, body: {
      columns: Object.entries(mapping).filter(([, f]) => !!f).map(([c, f]) => ({ column: Number(c), field: f })), version: job.version!,
      ...(save && name.trim() ? { saveAsName: name.trim() } : {}) } })),
    onSuccess: (j) => { refresh(); navigate(stepPath(j.id!, "validation")); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const sections = groupBySection(list);
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <div className="space-y-6 xl:col-span-2">
        {job.previousJobId && <Alert tone="warning" title="This file was imported before">The same file content was already imported. Duplicate rows will be detected and skipped.</Alert>}
        {!editable && <Alert tone="info">The mapping can no longer be changed for this import.</Alert>}
        <Card>
          <CardHeader title="Columns in the file" description={`${job.headers?.length ?? 0} columns${job.encoding ? ` · ${job.encoding}` : ""}${job.delimiter ? ` · ${job.delimiter} separated` : ""}${job.sheetName ? ` · sheet "${job.sheetName}"` : ""}`} />
          {fields.isPending ? <Skeleton className="m-6 h-64" /> : (
            <ul className="divide-y divide-border">
              {(job.headers ?? []).map((h, i) => {
                const entry = job.mapping?.find((m) => m.column === i);
                const chosen = mapping[i] ?? "";
                const duplicate = !!chosen && (usedCount.get(chosen) ?? 0) > 1;
                const values = (sample.data?.rows ?? []).map((r) => r.cells?.[i]).filter((v) => v != null && v !== "").slice(0, 3);
                return (
                  <li key={i} className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2 sm:px-6">
                    <div className="min-w-0">
                      <p className="flex flex-wrap items-center gap-2 font-medium text-fg">
                        <span className="truncate">{h}</span>
                        {entry?.source === "AUTO" && chosen === entry.field && <Badge tone="success">Matched</Badge>}
                        {entry?.source === "SAVED" && chosen === entry.field && <Badge tone="info">From saved mapping</Badge>}
                        {!chosen && <Badge tone="warning">Not imported</Badge>}
                      </p>
                      <p className="mt-1 truncate text-xs text-muted" title={values.join(" · ")}>{values.length ? values.join(" · ") : "No values in the first rows"}</p>
                      {entry?.note && !chosen && <p className="mt-1 flex items-start gap-1 text-xs text-warning-text"><AlertTriangle aria-hidden className="mt-0.5 h-3 w-3 shrink-0" />{entry.note}</p>}
                    </div>
                    <Field label={`Ivorovi field for "${h}"`} error={duplicate ? "This field is mapped to another column too." : undefined}>
                      {(f) => <Select {...f} value={chosen} disabled={!editable} onChange={(e) => setMapping((m) => ({ ...m, [i]: e.target.value }))}>
                        <option value="">Do not import this column</option>
                        {sections.map(([section, items]) => (
                          <optgroup key={section} label={items[0]!.sectionLabel}>
                            {items.map((x) => <option key={x.key} value={x.key}>{x.label}{x.required ? " (required)" : ""}</option>)}
                          </optgroup>
                        ))}
                      </Select>}
                    </Field>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
        <MappedPreview job={job} mapping={mapping} fields={byKey} rows={sample.data?.rows} masked={!!sample.data?.masked} loading={sample.isPending} />
      </div>
      <div className="space-y-6">
        <Card>
          <CardHeader title="Required fields" />
          <ul className="space-y-2 p-5 text-sm sm:p-6">
            {list.filter((f) => f.required || f.requiredWhen).map((f) => {
              const ok = usedCount.has(f.key!);
              return (
                <li key={f.key} className="flex items-start gap-2">
                  {ok ? <CheckCircle2 aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-success-text" /> : <Circle aria-hidden className={f.required ? "mt-0.5 h-4 w-4 shrink-0 text-danger-text" : "mt-0.5 h-4 w-4 shrink-0 text-muted"} />}
                  <span><span className="font-medium text-fg">{f.label}</span> <span className="text-muted">{f.required ? "Required" : f.requiredWhen}</span></span>
                </li>
              );
            })}
          </ul>
        </Card>
        {unmapped.length > 0 && (
          <Alert tone="warning" title={`${unmapped.length} column(s) will not be imported`}>{unmapped.join(", ")}</Alert>
        )}
        {problems.length > 0 && <Alert tone="danger" title="Fix the mapping">{problems.map((p) => <span key={p} className="block">{p}</span>)}</Alert>}
        {problem && <Alert tone="danger">{problem}</Alert>}
        {editable && (
          <Card>
            <CardBody className="space-y-4">
              <span className="flex items-center gap-2 text-sm"><Switch id="save-mapping" checked={save} onCheckedChange={setSave} label="Save this mapping" />
                <label htmlFor="save-mapping">Save as a reusable mapping</label></span>
              {save && <Field label="Mapping name">{(f) => <Input {...f} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder="e.g. Legacy ERP export" />}</Field>}
              <Button variant="primary" className="w-full" disabled={problems.length > 0 || (save && !name.trim())} loading={submit.isPending}
                onClick={() => { setProblem(null); submit.mutate(); }}><Save aria-hidden className="h-4 w-4" /> Save mapping and validate</Button>
            </CardBody>
          </Card>
        )}
      </div>
    </div>
  );
}

function groupBySection(list: StudentImportField[]): [string, StudentImportField[]][] {
  const out = new Map<string, StudentImportField[]>();
  for (const f of list) out.set(f.section!, [...(out.get(f.section!) ?? []), f]);
  return [...out.entries()];
}

function MappedPreview({ job, mapping, fields, rows, masked, loading }: {
  job: StudentImportJob; mapping: Record<number, string>; fields: Map<string, StudentImportField>;
  rows: { rowNo?: number; cells?: (string | null)[] }[] | undefined; masked: boolean; loading: boolean;
}) {
  const cols = Object.entries(mapping).filter(([, f]) => !!f).map(([c, f]) => ({ col: Number(c), field: fields.get(f) }));
  return (
    <Card>
      <CardHeader title="Preview of mapped values" description={`First rows of ${job.fileName} as they will be read${masked ? " (personal values hidden without permission)" : ""}.`} />
      {loading ? <Skeleton className="m-6 h-32" /> : cols.length === 0 ? <p className="p-6 text-sm text-muted">Map at least one column.</p> : (
        <div className="overflow-x-auto" tabIndex={0} role="region" aria-label="Mapped values (scrolls horizontally)">
          <table className="w-full min-w-max text-left text-sm">
            <caption className="sr-only">Mapped values of the first rows</caption>
            <thead className="bg-surface-muted text-xs uppercase tracking-wide text-muted">
              <tr><th scope="col" className="px-4 py-2">Row</th>{cols.map((c) => <th key={c.col} scope="col" className="px-4 py-2">{c.field?.label ?? "?"}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-border">
              {(rows ?? []).map((r) => (
                <tr key={r.rowNo}>
                  <td className="tabular px-4 py-2 text-muted">{r.rowNo}</td>
                  {cols.map((c) => <td key={c.col} className="max-w-56 truncate px-4 py-2">{r.cells?.[c.col] ?? <span className="text-muted">—</span>}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

export default ImportMappingPage;
