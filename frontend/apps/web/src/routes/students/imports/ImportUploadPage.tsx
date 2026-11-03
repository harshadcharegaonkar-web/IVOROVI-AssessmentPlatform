import { unwrap } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, Field, Input, PageHeader, Select } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Download, FileSpreadsheet, ShieldCheck, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatNumber } from "../../../lib/format";
import { useOrgLabels } from "../../../session/useOrgLabels";
import { UnitChoices } from "../../academics/AcademicsShared";
import { fileProblem, formatBytes } from "./importModel";
import { ImportStepper, stepPath, useDownload, useRefreshImports } from "./ImportShared";

/**
 * File upload: institution, optional saved mapping, file. The import key is created when a file is chosen, so a
 * repeated upload of the same file (double click, retry after a network error) returns the same job.
 */
export function ImportUploadPage() {
  const api = useApi();
  const navigate = useNavigate();
  const labels = useOrgLabels();
  const refresh = useRefreshImports();
  const download = useDownload();
  const [institutionId, setInstitutionId] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const key = useRef<string>(crypto.randomUUID());
  const overview = useQuery({ queryKey: ["student-imports", "overview"], queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/overview")) });
  const mappings = useQuery({ queryKey: ["student-imports", "mappings", institutionId], enabled: !!institutionId,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/mappings", { params: { query: { institutionId } } })) });
  const maxBytes = overview.data?.limits?.maxFileBytes ?? 10 * 1024 * 1024;
  const check = file ? fileProblem(file, maxBytes) : null;
  const upload = useMutation({
    mutationFn: async () => {
      const form = new FormData();
      form.append("file", file!);
      return unwrap(await api.POST("/api/v1/student-imports", {
        params: { query: { institutionId, importKey: key.current, ...(templateId ? { mappingTemplateId: templateId } : {}) } },
        body: form as unknown as { file: string }, bodySerializer: (b: unknown) => b as FormData,
      }));
    },
    onSuccess: (job) => { refresh(); navigate(stepPath(job.id!, "mapping")); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const template = (format: "XLSX" | "CSV") => download(() => api.GET("/api/v1/student-imports/template", {
    params: { query: { format, ...(institutionId ? { institutionId } : {}) } }, parseAs: "blob" }), `ivorovi-student-import-template.${format === "CSV" ? "csv" : "xlsx"}`);
  return (
    <>
      <Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Imports", to: "/students/imports" }, { label: "New import" }]} />
      <PageHeader title="New student import" description="Upload one file per university or college. Nothing is imported until you have reviewed the preview and confirmed." />
      <ImportStepper job={undefined} active="upload" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader title="File" description="Excel workbook (.xlsx, first visible sheet) or CSV (UTF-8, UTF-16 or Windows-1252; comma, semicolon or tab separated)." />
          <CardBody className="space-y-5">
            {problem && <Alert tone="danger" title="The file was not accepted">{problem}</Alert>}
            <Field label={`${labels.singular("UNIVERSITY")} or ${labels.singular("COLLEGE").toLowerCase()}`} hint="Rows are imported into this institution and checked against its academic structure.">
              {(f) => <Select {...f} value={institutionId} onChange={(e) => { setInstitutionId(e.target.value); setTemplateId(""); }}>
                <option value="">Choose…</option><UnitChoices permission="student.import" types={["UNIVERSITY", "COLLEGE"]} /></Select>}
            </Field>
            <Field label="Saved column mapping (optional)" hint="Reuse the mapping of an earlier import with the same column headings.">
              {(f) => <Select {...f} value={templateId} disabled={!institutionId} onChange={(e) => setTemplateId(e.target.value)}>
                <option value="">Match columns automatically</option>
                {(mappings.data ?? []).map((m) => <option key={m.id} value={m.id}>{m.name}{m.institutionId ? "" : " (organization)"}</option>)}
              </Select>}
            </Field>
            <Field label="Spreadsheet" error={check ?? undefined} hint={`At most ${formatBytes(maxBytes)} and ${formatNumber(overview.data?.limits?.maxRows ?? 10000)} rows. The first row holds the column headings.`}>
              {(f) => <Input {...f} type="file" accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
                onChange={(e) => { setProblem(null); setFile(e.target.files?.[0] ?? null); key.current = crypto.randomUUID(); }} />}
            </Field>
            {file && !check && (
              <p className="flex items-center gap-2 text-sm text-muted"><FileSpreadsheet aria-hidden className="h-4 w-4" />{file.name} · {formatBytes(file.size)}</p>
            )}
            <div className="flex flex-wrap justify-end gap-3 border-t border-border pt-5">
              <ButtonLink to="/students/imports">Cancel</ButtonLink>
              <Button variant="primary" disabled={!institutionId || !file || !!check} loading={upload.isPending} onClick={() => { setProblem(null); upload.mutate(); }}>
                <Upload aria-hidden className="h-4 w-4" /> Upload and continue
              </Button>
            </div>
          </CardBody>
        </Card>
        <div className="space-y-6">
          <Card>
            <CardHeader title="Standard templates" description="Column headings, an instructions sheet and the reference codes of the chosen institution." />
            <CardBody className="flex flex-col gap-3">
              <Button onClick={() => void template("XLSX")}><Download aria-hidden className="h-4 w-4" /> Excel template (.xlsx)</Button>
              <Button onClick={() => void template("CSV")}><Download aria-hidden className="h-4 w-4" /> CSV template (headings only)</Button>
              <ButtonLink to="/students/imports/templates" variant="ghost">Choose sections and manage mappings</ButtonLink>
            </CardBody>
          </Card>
          <Alert tone="info" title="How your file is protected">
            <span className="flex gap-2"><ShieldCheck aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
              Files are scanned for malware, macros are refused and formulas are never evaluated. File-type checks alone are not malware protection.
              The file is stored only for your organization and deleted after the retention period.</span>
          </Alert>
        </div>
      </div>
    </>
  );
}

export default ImportUploadPage;
