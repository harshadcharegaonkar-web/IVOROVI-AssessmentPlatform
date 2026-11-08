import { unwrap, type QuestionAsset } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, Field, Input, PageHeader, type BadgeTone, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Upload } from "lucide-react";
import { useRef, useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useImageSources } from "./QuestionShared";

const SCAN_TONE: Record<string, BadgeTone> = { CLEAN: "success", NOT_SCANNED_DEV: "warning", INFECTED: "danger", ERROR: "danger", PENDING: "info" };
const SCAN_LABEL: Record<string, string> = { CLEAN: "Clean", NOT_SCANNED_DEV: "Not scanned (development)", INFECTED: "Blocked: malware", ERROR: "Blocked: scan failed", PENDING: "Scanning" };
const mb = (n?: number) => `${((n ?? 0) / 1024 / 1024).toFixed(2)} MB`;

/** Image library of a bank: quarantined upload, malware scan, re-encoding, institution quota. */
export function QuestionAssetsPage() {
  const { bankId = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const file = useRef<HTMLInputElement>(null);
  const [alt, setAlt] = useState("");
  const bank = useQuery({ queryKey: ["question-bank", bankId], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{id}", { params: { path: { id: bankId } } })) });
  const list = useQuery({ queryKey: ["question-assets", bankId], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{bankId}/assets", { params: { path: { bankId } } })) });
  const quota = useQuery({ queryKey: ["question-quota", bankId], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{bankId}/asset-quota", { params: { path: { bankId } } })) });
  const upload = useMutation({
    mutationFn: async (f: File) => {
      const form = new FormData();
      form.append("file", f);
      return unwrap(await api.POST("/api/v1/question-banks/{bankId}/assets", { params: { path: { bankId }, query: alt ? { altText: alt } : {} },
        body: form as never, bodySerializer: (b: unknown) => b as FormData }));
    },
    onSuccess: () => { setAlt(""); if (file.current) file.current.value = ""; void qc.invalidateQueries({ queryKey: ["question-assets", bankId] }); void qc.invalidateQueries({ queryKey: ["question-quota", bankId] }); },
  });
  const thumbs = useImageSources((list.data ?? []).filter((a) => a.scanStatus === "CLEAN" || a.scanStatus === "NOT_SCANNED_DEV")
    .map((a) => ({ format: "ivorovi.content.v1" as const, blocks: [{ type: "image" as const, asset: a.id!, decorative: true }] })));
  const columns: Column<QuestionAsset>[] = [
    { key: "img", header: "Image", cell: (a) => { const s = thumbs(a.id!); return s ? <img src={s} alt={a.altText ?? ""} className="h-12 w-16 rounded object-contain" /> : <span className="text-xs text-muted">—</span>; } },
    { key: "name", header: "File", cell: (a) => <span className="text-sm">{a.fileName}<span className="block text-xs text-muted">{a.altText}</span></span> },
    { key: "scan", header: "Malware scan", cell: (a) => <Badge tone={SCAN_TONE[a.scanStatus ?? ""] ?? "neutral"}>{SCAN_LABEL[a.scanStatus ?? ""] ?? a.scanStatus}</Badge> },
    { key: "size", header: "Size", cell: (a) => `${a.width}×${a.height} · ${mb(a.bytes)}`, hideOnMobile: true },
    { key: "used", header: "Used by", cell: (a) => `${a.usedByVersions} version(s)`, hideOnMobile: true },
    { key: "at", header: "Uploaded", cell: (a) => formatDateTime(a.createdAt), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title={`Images · ${bank.data?.name ?? ""}`} description="PNG or JPEG up to 5 MB and 4096 × 4096. Every image is scanned for malware before it can be used, and re-encoded without metadata." />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <DataTable caption="Images" columns={columns} rows={list.data} rowKey={(a) => a.id!} loading={list.isPending} error={list.isError ? errorInfo(list.error) : null}
            onRetry={() => void list.refetch()} empty={{ title: "No images yet", description: "Upload diagrams or figures to use in questions." }} />
        </Card>
        <div className="space-y-6">
          {bank.data?.canAuthor && <Card>
            <CardHeader title="Upload" />
            <CardBody className="space-y-3">
              {upload.isError && <Alert tone="danger">{errorInfo(upload.error).message}</Alert>}
              {upload.data && upload.data.scanStatus !== "CLEAN" && upload.data.scanStatus !== "NOT_SCANNED_DEV" &&
                <Alert tone="danger" title="Image blocked">The malware scan did not pass ({SCAN_LABEL[upload.data.scanStatus ?? ""]}). It stays in quarantine and cannot be used.</Alert>}
              <Field label="Description (alternative text)">{(p) => <Input {...p} value={alt} maxLength={250} onChange={(e) => setAlt(e.target.value)} />}</Field>
              <input ref={file} type="file" accept="image/png,image/jpeg" aria-label="Image file" className="block text-sm"
                onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); }} />
              <Button variant="secondary" loading={upload.isPending} onClick={() => file.current?.click()}><Upload aria-hidden className="h-4 w-4" /> Choose image</Button>
            </CardBody>
          </Card>}
          <Card>
            <CardHeader title="Institution storage" />
            <CardBody className="text-sm">
              {quota.data && <>
                <p>{mb(quota.data.usedBytes)} of {mb(quota.data.limitBytes)} used</p>
                <div className="mt-2 h-2 rounded bg-bg" role="progressbar" aria-label="Storage used" aria-valuemin={0} aria-valuemax={100}
                  aria-valuenow={Math.round(((quota.data.usedBytes ?? 0) / Math.max(1, quota.data.limitBytes ?? 1)) * 100)}>
                  <div className="h-2 rounded bg-primary" style={{ width: `${Math.min(100, ((quota.data.usedBytes ?? 0) / Math.max(1, quota.data.limitBytes ?? 1)) * 100)}%` }} />
                </div>
                <p className="mt-2 text-xs text-muted">Images not used by any question version are removed after 30 days.</p>
              </>}
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}

export default QuestionAssetsPage;
