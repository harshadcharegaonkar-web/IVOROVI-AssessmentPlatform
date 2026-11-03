import { unwrap, type StudentImportJob } from "@ivorovi/api-client";
import { Badge, Breadcrumbs, ButtonLink, Card, cn, PageHeader, Skeleton, useToast } from "@ivorovi/ui";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Check } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime } from "../../../lib/format";
import { DetailError } from "../../academics/AcademicsShared";
import { currentStep, formatBytes, isRunning, jobStatusText, jobStatusTone, reachable, STEPS, type Step } from "./importModel";

/** Every import query key starts with "student-imports"; jobs poll while queued, validating or importing. */
export function useImportJob(id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["student-imports", "job", id], enabled: !!id,
    refetchInterval: (q) => (isRunning(q.state.data?.status) ? 1500 : false),
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/{id}", { params: { path: { id: id! } } })),
  });
}

export function useRefreshImports() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ["student-imports"] });
    void qc.invalidateQueries({ queryKey: ["students"] });
  };
}

export function JobStatusBadge({ status }: { status: string | undefined }) {
  return <Badge tone={jobStatusTone(status)}>{jobStatusText(status)}</Badge>;
}

/** Progress bar with an accessible value. */
export function ProgressBar({ value, label }: { value: number; label: string }) {
  return (
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={value} className="h-2.5 w-full overflow-hidden rounded-full bg-border">
      <div className="h-2.5 rounded-full bg-primary transition-[width] duration-500" style={{ width: `${value}%` }} />
    </div>
  );
}

/** The import steps; the current one is marked, reachable earlier and later steps are links. */
export function ImportStepper({ job, active }: { job: StudentImportJob | undefined; active: Step }) {
  const activeIndex = STEPS.findIndex((s) => s.step === active);
  const strip = useRef<HTMLElement>(null);
  useEffect(() => {
    // on narrow screens the strip scrolls: keep the current step visible
    const nav = strip.current;
    const current = nav?.querySelector<HTMLElement>("[aria-current=step]");
    if (nav && current) nav.scrollLeft = Math.max(0, current.offsetLeft - 16);
  }, [active]);
  return (
    <nav ref={strip} aria-label="Import steps" className="relative mb-6 overflow-x-auto">
      <ol className="flex min-w-max items-center gap-1 text-sm">
        {STEPS.map((s, i) => {
          const done = i < activeIndex;
          const current = s.step === active;
          const link = job && !current && reachable(job, s.step);
          const content = (
            <span className={cn("inline-flex items-center gap-2 rounded-full px-3 py-1.5", current ? "bg-primary text-primary-contrast font-semibold" : done ? "text-fg" : "text-muted-strong")}>
              <span aria-hidden className={cn("flex h-5 w-5 items-center justify-center rounded-full text-xs", current ? "bg-white/20" : done ? "bg-success-soft text-success-text" : "bg-surface-muted")}>
                {done ? <Check className="h-3 w-3" /> : i + 1}
              </span>
              {s.label}
            </span>
          );
          return (
            <li key={s.step} className="flex items-center gap-1" aria-current={current ? "step" : undefined}>
              {link ? <ButtonLink to={stepPath(job!.id!, s.step)} variant="ghost" size="sm" className="!px-0">{content}</ButtonLink> : content}
              {i < STEPS.length - 1 && <span aria-hidden className="mx-1 h-px w-4 bg-border" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function stepPath(id: string, step: Step): string {
  return step === "upload" ? "/students/imports/new" : `/students/imports/${id}/${step}`;
}

/** Loads the job of the route and renders the header, the stepper and the step's content. */
export function ImportJobFrame({ step, title, description, actions, children }: {
  step: Step; title: string; description?: ReactNode; actions?: (job: StudentImportJob) => ReactNode; children: (job: StudentImportJob) => ReactNode;
}) {
  const { id } = useParams();
  const q = useImportJob(id);
  const job = q.data;
  return (
    <>
      <Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Imports", to: "/students/imports" }, { label: job?.fileName ?? "Import" }]} />
      <PageHeader title={title} description={description} actions={job && actions ? actions(job) : undefined} />
      {q.isPending ? <Skeleton className="h-96 w-full" /> : q.isError ? (
        <Card><DetailError error={q.error} what="Import" onRetry={() => void q.refetch()} /></Card>
      ) : (
        <>
          <ImportStepper job={job} active={step} />
          <JobSummaryLine job={job!} />
          {children(job!)}
        </>
      )}
    </>
  );
}

export function JobSummaryLine({ job }: { job: StudentImportJob }) {
  return (
    <Card className="mb-6">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 p-5 text-sm sm:grid-cols-3 lg:grid-cols-6 sm:p-6">
        <Fact label="File" value={<span className="block truncate" title={job.fileName}>{job.fileName}</span>} />
        <Fact label="Institution" value={job.institutionName} />
        <Fact label="Status" value={<JobStatusBadge status={job.status} />} />
        <Fact label="Rows" value={<span className="tabular">{job.counts?.total ?? 0}</span>} />
        <Fact label="Format" value={`${job.fileFormat} · ${formatBytes(job.fileSize)}`} />
        <Fact label="Uploaded" value={<span className="block truncate">{formatDateTime(job.createdAt)} · {job.createdByName}</span>} />
      </dl>
    </Card>
  );
}

function Fact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs font-medium uppercase tracking-wide text-muted">{label}</dt>
      <dd className="mt-1 min-w-0 font-medium text-fg">{value}</dd>
    </div>
  );
}

/** Where a job continues (used by the job index route and the dashboard). */
export function continuePath(job: StudentImportJob): string {
  return stepPath(job.id!, currentStep(job));
}

/** Downloads a report or template through the API (authenticated, audited) and saves it. */
export function useDownload() {
  const api = useApi();
  const toast = useToast();
  return async (run: () => Promise<{ data?: unknown; error?: unknown; response: Response }>, fileName: string) => {
    try {
      const r = await run();
      const blob = unwrap(r as never) as unknown as Blob;
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      a.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      toast.show("danger", "Download failed", errorInfo(e).message);
    }
    return api;
  };
}

export type ReportKind = "validation" | "errors" | "result" | "corrections";

export const REPORT_TEXT: Record<ReportKind, { label: string; description: string }> = {
  validation: { label: "Validation report", description: "Every issue with row, column, category and recommended correction." },
  errors: { label: "Error report", description: "Errors only (validation and import failures) with recommended corrections." },
  result: { label: "Result report", description: "The final outcome of every source row." },
  corrections: { label: "Rows to correct", description: "The original values of rows that were not imported, with their errors, ready to fix and upload again." },
};

/** Downloads one report of a job. */
export function useReportDownload(job: StudentImportJob) {
  const api = useApi();
  const download = useDownload();
  const base = (job.fileName ?? "import").replace(/\.[A-Za-z0-9]+$/, "");
  return (kind: ReportKind, format: "CSV" | "XLSX" = "CSV") => download(
    () => api.GET("/api/v1/student-imports/{id}/reports/{kind}", { params: { path: { id: job.id!, kind }, query: { format } }, parseAs: "blob" }),
    `${base}-${kind}.${format === "CSV" ? "csv" : "xlsx"}`);
}
