import { unwrap } from "@ivorovi/api-client";
import { Badge, type BadgeTone, ButtonLink, Card, CardHeader, EmptyState, ErrorState, Field, Input, KpiCard, Link, PageHeader } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Clock, FileUp, Gauge, Loader, Rows3, XCircle } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber, formatRelative, humanize } from "../../lib/format";
import { useInstitutionContext } from "../../session/InstitutionContext";
import { kpi, MonthlyColumns, SectionTitle } from "./Charts";

const tone: Record<string, BadgeTone> = { COMPLETED: "success", PARTIALLY_COMPLETED: "warning", FAILED: "danger", CANCELLED: "neutral" };
const secs = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `${formatNumber(v)} s`);

/** Import operations: outcomes, records processed and rejected, durations and recent jobs, with a date range. */
export function ImportOperationsPage() {
  const api = useApi();
  const { selectedId } = useInstitutionContext();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const q = useQuery({ queryKey: ["insights", "imports", selectedId, from, to], queryFn: async () =>
    unwrap(await api.GET("/api/v1/insights/imports", { params: { query: { ...(selectedId ? { orgUnitId: selectedId } : {}), ...(from ? { from } : {}), ...(to ? { to } : {}) } } })) });
  const d = q.data;
  const loading = q.isPending;
  return (
    <>
      <PageHeader title="Import operations" description="Bulk student imports in your scope."
        actions={<><ButtonLink to="/students/imports/history">Import history</ButtonLink><ButtonLink to="/students/imports/new" variant="primary"><FileUp aria-hidden className="h-4 w-4" /> New import</ButtonLink></>} />
      <div className="mb-6 flex flex-wrap items-end gap-4" role="group" aria-label="Date range">
        <Field label="From">{(p) => <Input {...p} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />}</Field>
        <Field label="To">{(p) => <Input {...p} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />}</Field>
      </div>
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card> : (
        <>
          <section aria-label="Import outcomes" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard testId="kpi-import-total" icon={FileUp} label="Imports" value={kpi(d?.totalJobs)} secondary={`${kpi(d?.inProgress)} in progress · ${kpi(d?.awaitingAction)} awaiting action`} loading={loading} />
            <KpiCard testId="kpi-import-completed" icon={CheckCircle2} label="Completed" value={kpi(d?.completed)} loading={loading} />
            <KpiCard testId="kpi-import-partial" icon={AlertTriangle} label="Partially completed" value={kpi(d?.partiallyCompleted)} loading={loading} />
            <KpiCard testId="kpi-import-failed" icon={XCircle} label="Failed" value={kpi(d?.failed)} secondary={`${kpi(d?.cancelled)} cancelled`} loading={loading} />
          </section>
          <SectionTitle>Records and duration</SectionTitle>
          <section aria-label="Records and duration" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard icon={Rows3} label="Records processed" value={kpi(d?.recordsProcessed)} secondary={`${kpi(d?.recordsImported)} created · ${kpi(d?.recordsUpdated)} updated`} loading={loading} />
            <KpiCard testId="kpi-import-rejected" icon={XCircle} label="Records rejected" value={kpi(d?.recordsRejected)} secondary="Invalid, conflicting or failed rows" loading={loading} />
            <KpiCard icon={Clock} label="Processing time (median)" value={secs(d?.medianDurationSeconds)} secondary={`Average ${secs(d?.averageDurationSeconds)} · 95th percentile ${secs(d?.p95DurationSeconds)}`} loading={loading} />
            <KpiCard icon={Gauge} label="Throughput" value={d?.averageRowsPerSecond == null ? "—" : `${formatNumber(d.averageRowsPerSecond)} rows/s`} secondary="Average per import" loading={loading} />
          </section>
          <div className="mt-6 grid grid-cols-1 gap-6 xl:grid-cols-2">
            <MonthlyColumns title="Imports per month" points={d?.jobsByMonth} loading={loading} testId="imports-by-month" />
            <Card>
              <CardHeader title="Recent imports" actions={<Link to="/students/imports/history" className="text-sm font-medium text-primary hover:underline">View all</Link>} />
              {loading ? <div className="h-40" /> : (d?.recent ?? []).length === 0 ? <EmptyState title="No imports yet" /> : (
                <ul className="divide-y divide-border">
                  {d!.recent!.map((j) => (
                    <li key={j.id} className="flex items-start gap-3 px-5 py-3 sm:px-6">
                      <Loader aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-muted" />
                      <div className="min-w-0 flex-1">
                        <Link to={`/students/imports/${j.id}`} className="block truncate text-sm font-semibold text-fg hover:underline">{j.fileName}</Link>
                        <p className="truncate text-xs text-muted">{j.institutionName} · {formatNumber(j.totalRows)} rows · {formatNumber(j.imported)} imported · {formatNumber(j.failed)} failed
                          {j.durationSeconds != null ? ` · ${formatNumber(j.durationSeconds)} s` : ""}</p>
                      </div>
                      <div className="shrink-0 text-right">
                        <Badge tone={tone[j.status ?? ""] ?? "info"}>{humanize(j.status)}</Badge>
                        <p className="mt-1 text-xs text-muted">{formatRelative(j.createdAt)}</p>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
        </>
      )}
    </>
  );
}

export default ImportOperationsPage;
