import { unwrap, type StudentDetail, type StudentDocument } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardHeader, DataTable, Field, Input, Menu, MenuItem, Modal, Select, Switch, Tooltip, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Download, MoreHorizontal, ShieldAlert, Upload } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { StatusDialog } from "../academics/AcademicsShared";
import { ACCEPTED_TYPES, fileSize, SCAN_TEXT, titleCase, uploadProblem } from "./studentModel";
import { useInvalidateStudents, useStudentOptions } from "./StudentShared";

/**
 * Student documents. Uploads stay quarantined until the malware scanner reports them clean; quarantined, infected or
 * failed documents cannot be opened, verified or processed. File checks are not malware protection.
 */
export function DocumentsTab({ detail }: { detail: StudentDetail }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const s = detail.student!;
  const manage = !!detail.can?.manageDocuments && !!s.active;
  const [removed, setRemoved] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [action, setAction] = useState<{ doc: StudentDocument; kind: "verify" | "reject" | "remove" } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["students", "documents", s.id, removed], refetchInterval: (query) => (query.state.data ?? []).some((d) => d.scanStatus === "PENDING") ? 10_000 : false,
    queryFn: async () => unwrap(await api.GET("/api/v1/students/{studentId}/documents", { params: { path: { studentId: s.id! }, query: { includeRemoved: removed } } })) });
  const download = async (d: StudentDocument) => {
    try {
      const r = await api.GET("/api/v1/students/{studentId}/documents/{documentId}/content", { params: { path: { studentId: s.id!, documentId: d.id! } }, parseAs: "blob" });
      const blob = unwrap(r) as unknown as Blob;
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = d.fileName ?? "document";
      a.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      toast.show("danger", "Download refused", errorInfo(e).message);
    }
  };
  const act = useMutation({
    mutationFn: async ({ doc, kind, text }: { doc: StudentDocument; kind: "verify" | "reject" | "remove" | "rescan"; text: string }) => {
      const path = { studentId: s.id!, documentId: doc.id! };
      if (kind === "remove") return unwrap(await api.POST("/api/v1/students/{studentId}/documents/{documentId}/remove", { params: { path }, body: { reason: text } }));
      if (kind === "rescan") return unwrap(await api.POST("/api/v1/students/{studentId}/documents/{documentId}/rescan", { params: { path } }));
      return unwrap(await api.POST("/api/v1/students/{studentId}/documents/{documentId}/verification", { params: { path },
        body: { status: kind === "verify" ? "VERIFIED" : "REJECTED", ...(text ? { note: text } : {}) } }));
    },
    onSuccess: (_d, v) => { invalidate(); setAction(null); toast.show("success", v.kind === "remove" ? "Document removed" : v.kind === "rescan" ? "Queued for scanning" : v.kind === "verify" ? "Document verified" : "Document rejected"); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const columns: Column<StudentDocument>[] = [
    { key: "title", header: "Document", cell: (d) => <span className="block min-w-0"><span className="block truncate font-medium">{d.title}</span>
      <span className="block truncate text-xs text-muted">{d.categoryLabel} · {d.fileName} · {fileSize(d.sizeBytes)}</span>
      <span className="mt-1 block md:hidden"><Badge tone={SCAN_TEXT[d.scanStatus ?? "PENDING"]!.tone}>{SCAN_TEXT[d.scanStatus ?? "PENDING"]!.label}</Badge></span></span> },
    { key: "scan", header: "Malware scan", hideOnMobile: true, cell: (d) => { const t = SCAN_TEXT[d.scanStatus ?? "PENDING"]!;
      return <Tooltip content={t.help} side="bottom"><span className="inline-flex"><Badge tone={t.tone}>{t.label}</Badge></span></Tooltip>; } },
    { key: "verification", header: "Verification", hideOnMobile: true, cell: (d) => d.status === "REMOVED" ? <Badge>Removed</Badge>
      : <span className="block min-w-0"><Badge tone={d.verificationStatus === "VERIFIED" ? "success" : d.verificationStatus === "REJECTED" ? "danger" : "neutral"}>{titleCase(d.verificationStatus)}</Badge>
        {d.verifiedByName && <span className="mt-0.5 block truncate text-xs text-muted">{d.verifiedByName}</span>}</span> },
    { key: "uploaded", header: "Uploaded", hideOnMobile: true, cell: (d) => <span className="block min-w-0"><span className="block">{formatDateTime(d.uploadedAt)}</span>
      <span className="block truncate text-xs text-muted">{d.uploadedByName}</span></span> },
    { key: "actions", header: "Actions", cell: (d) => (
      <span className="flex items-center gap-1">
        <Button size="sm" variant="ghost" disabled={!d.downloadable} aria-label={d.downloadable ? `Download ${d.title}` : `${d.title} is not available (${SCAN_TEXT[d.scanStatus ?? "PENDING"]!.label})`}
          onClick={() => void download(d)}><Download aria-hidden className="h-4 w-4" /></Button>
        {manage && d.status === "ACTIVE" && <Menu trigger={<Button size="sm" variant="ghost" aria-label={`Actions for ${d.title}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
          {d.scanStatus === "CLEAN" && d.verificationStatus !== "VERIFIED" && <MenuItem onSelect={() => { setProblem(null); setAction({ doc: d, kind: "verify" }); }}>Verify…</MenuItem>}
          {d.scanStatus === "CLEAN" && d.verificationStatus !== "REJECTED" && <MenuItem onSelect={() => { setProblem(null); setAction({ doc: d, kind: "reject" }); }}>Reject…</MenuItem>}
          {d.scanStatus === "ERROR" && <MenuItem onSelect={() => act.mutate({ doc: d, kind: "rescan", text: "" })}>Scan again</MenuItem>}
          <MenuItem tone="danger" onSelect={() => { setProblem(null); setAction({ doc: d, kind: "remove" }); }}>Remove…</MenuItem>
        </Menu>}
      </span>) },
  ];
  const quarantined = (q.data ?? []).filter((d) => d.status === "ACTIVE" && d.scanStatus !== "CLEAN").length;
  return (
    <Card>
      <CardHeader title="Documents" description="PDF, JPEG or PNG up to 5 MB. Every upload is quarantined until a malware scan reports it clean."
        actions={<div className="flex flex-wrap items-center gap-3">
          <span className="flex items-center gap-2 text-sm"><Switch id="doc-removed" checked={removed} onCheckedChange={setRemoved} label="Show removed documents" /><label htmlFor="doc-removed">Show removed</label></span>
          {manage && <Button size="sm" onClick={() => setUploading(true)}><Upload aria-hidden className="h-4 w-4" /> Upload</Button>}
        </div>} />
      {quarantined > 0 && <div className="px-5 pt-4 sm:px-6"><Alert tone="warning" title="Quarantined documents">
        <span className="inline-flex items-start gap-2"><ShieldAlert aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
          {quarantined} document(s) cannot be opened until the malware scanner reports them clean. File-type checks alone are not malware protection.</span></Alert></div>}
      <DataTable caption="Student documents" columns={columns} rows={q.data} rowKey={(d) => d.id!} loading={q.isPending}
        error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
        empty={{ title: "No documents", description: manage ? "Upload identity proof, previous certificates, transfer or migration certificates, or a photograph." : undefined }} />
      {uploading && <UploadDialog studentId={s.id!} onClose={() => setUploading(false)} />}
      {action && <StatusDialog open onOpenChange={(o) => !o && setAction(null)} busy={act.isPending} error={problem}
        reasonRequired={action.kind !== "verify"} reasonLabel={action.kind === "verify" ? "Note" : "Reason"} tone={action.kind === "verify" ? "primary" : "danger"}
        title={action.kind === "verify" ? `Verify ${action.doc.title}?` : action.kind === "reject" ? `Reject ${action.doc.title}?` : `Remove ${action.doc.title}?`}
        confirmLabel={action.kind === "verify" ? "Verify" : action.kind === "reject" ? "Reject" : "Remove"}
        description={action.kind === "remove" ? "The record is kept; the stored file is purged after the retention period." : "Confirms whether the document matches the original."}
        onConfirm={(text) => act.mutate({ doc: action.doc, kind: action.kind, text })} />}
    </Card>
  );
}

function UploadDialog({ studentId, onClose }: { studentId: string; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const options = useStudentOptions();
  const [category, setCategory] = useState("IDENTITY_PROOF");
  const [title, setTitle] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async () => {
      const form = new FormData();
      form.append("file", file!);
      return unwrap(await api.POST("/api/v1/students/{studentId}/documents", { params: { path: { studentId }, query: { category, ...(title.trim() ? { title: title.trim() } : {}) } },
        body: form as unknown as { file: string }, bodySerializer: (b: unknown) => b as FormData }));
    },
    onSuccess: () => { invalidate(); toast.show("success", "Uploaded to quarantine", "It can be opened once the malware scan reports it clean."); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const check = file ? uploadProblem(file, category) : null;
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Upload a document" description="The server checks the file content; the malware scan decides whether it can be opened."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={run.isPending} disabled={!file || !!check} onClick={() => { setProblem(null); run.mutate(); }}>Upload</Button></>}>
      <div className="space-y-4">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Field label="Category">{(f) => <Select {...f} value={category} onChange={(e) => setCategory(e.target.value)}>
          {options.of("DOCUMENT_CATEGORY").map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}</Select>}</Field>
        <Field label="Title (optional)">{(f) => <Input {...f} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}</Field>
        <Field label="File" error={check ?? undefined} hint="PDF, JPEG or PNG, at most 5 MB.">
          {(f) => <Input {...f} type="file" accept={ACCEPTED_TYPES.join(",")} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />}
        </Field>
      </div>
    </Modal>
  );
}
