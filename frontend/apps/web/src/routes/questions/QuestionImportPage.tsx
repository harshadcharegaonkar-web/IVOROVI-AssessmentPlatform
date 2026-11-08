import { unwrap, type QuestionImportRow } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, PageHeader, Switch, type BadgeTone, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { humanize } from "../../lib/format";

const TONE: Record<string, BadgeTone> = { VALID: "success", INVALID: "danger", DUPLICATE_IN_FILE: "warning", DUPLICATE_IN_BANK: "warning", NEAR_DUPLICATE: "info" };

/** Bulk import: scanned upload, preview with validation and duplicate detection, commit into drafts. */
export function QuestionImportPage() {
  const { bankId = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const [importId, setImportId] = useState<string | null>(null);
  const [near, setNear] = useState(false);
  const upload = useMutation({
    mutationFn: async (f: File) => {
      const form = new FormData();
      form.append("file", f);
      return unwrap(await api.POST("/api/v1/question-banks/{bankId}/imports", { params: { path: { bankId } }, body: form as never, bodySerializer: (b: unknown) => b as FormData }));
    },
    onSuccess: (i) => setImportId(i.id!),
  });
  const imp = useQuery({ queryKey: ["question-import", importId], enabled: !!importId,
    queryFn: async () => unwrap(await api.GET("/api/v1/question-imports/{id}", { params: { path: { id: importId! } } })) });
  const rows = useQuery({ queryKey: ["question-import", importId, "rows"], enabled: !!importId,
    queryFn: async () => unwrap(await api.GET("/api/v1/question-imports/{id}/rows", { params: { path: { id: importId! } } })) });
  const commit = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/question-imports/{id}/commit", { params: { path: { id: importId! }, query: { includeNearDuplicates: near } } })),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["question-import", importId] }); void qc.invalidateQueries({ queryKey: ["questions"] }); },
  });
  const template = async () => {
    const r = await api.GET("/api/v1/question-imports/template", { parseAs: "blob" });
    const blob = r.data as Blob;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "ivorovi-question-import-template.csv";
    a.click();
    URL.revokeObjectURL(a.href);
  };
  const columns: Column<QuestionImportRow>[] = [
    { key: "row", header: "Row", cell: (r) => r.rowNo },
    { key: "outcome", header: "Result", cell: (r) => <Badge tone={TONE[r.outcome ?? ""] ?? "neutral"}>{humanize(r.outcome)}</Badge> },
    { key: "q", header: "Question", cell: (r) => <span className="text-sm">{r.excerpt}<span className="block text-xs text-danger">{(r.errors ?? []).join(" ")}</span></span> },
    { key: "type", header: "Type", cell: (r) => r.type, hideOnMobile: true },
  ];
  const i = imp.data;
  return (
    <>
      <PageHeader title="Import questions" description="CSV or XLSX, scanned for malware first. Every row is checked with the editor's rules; nothing is created until you commit, and imported questions start as your drafts."
        actions={<Button variant="secondary" onClick={() => void template()}><Download aria-hidden className="h-4 w-4" /> Template</Button>} />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card>
          <CardHeader title="File" />
          <CardBody className="space-y-3">
            {upload.isError && <Alert tone="danger">{errorInfo(upload.error).message}</Alert>}
            <input type="file" accept=".csv,.xlsx" aria-label="Import file" className="block text-sm" onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); }} />
            {upload.isPending && <p className="text-sm text-muted">Scanning and checking…</p>}
            {i && <dl className="grid grid-cols-2 gap-2 text-sm" data-testid="import-summary">
              <dt className="text-muted">Rows</dt><dd>{i.totalRows}</dd><dt className="text-muted">Valid</dt><dd>{i.validRows}</dd>
              <dt className="text-muted">Invalid</dt><dd>{i.invalidRows}</dd><dt className="text-muted">Duplicates</dt><dd>{i.duplicateRows}</dd>
              <dt className="text-muted">Near duplicates</dt><dd>{i.nearDuplicateRows}</dd><dt className="text-muted">Created</dt><dd>{i.createdRows}</dd>
            </dl>}
            {i?.status === "PREVIEWED" && <>
              <Switch label="Also create near duplicates" checked={near} onCheckedChange={setNear} />
              {commit.isError && <Alert tone="danger">{errorInfo(commit.error).message}</Alert>}
              <Button variant="primary" loading={commit.isPending} disabled={(i.validRows ?? 0) + (near ? i.nearDuplicateRows ?? 0 : 0) === 0} onClick={() => commit.mutate()}>Create drafts</Button>
            </>}
            {i?.status === "COMMITTED" && <Alert tone="success" title="Imported">{i.createdRows} draft question(s) created. Submit them for review from the bank.</Alert>}
          </CardBody>
        </Card>
        <Card className="lg:col-span-2">
          <DataTable caption="Rows" columns={columns} rows={rows.data} rowKey={(r) => String(r.rowNo)} loading={!!importId && rows.isPending}
            error={rows.isError ? errorInfo(rows.error) : null} onRetry={() => void rows.refetch()}
            empty={{ title: "No file yet", description: "Download the template, fill it in and upload it." }} />
        </Card>
      </div>
    </>
  );
}

export default QuestionImportPage;
