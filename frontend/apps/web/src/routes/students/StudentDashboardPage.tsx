import { unwrap, type StudentGroupCount } from "@ivorovi/api-client";
import { ButtonLink, Card, CardHeader, EmptyState, ErrorState, KpiCard, Link, PageHeader } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { FileClock, GraduationCap, Pause, Plus, UserCheck, UserMinus, UserPlus, Users } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";

/** Student dashboard: KPIs and distributions from the caller's authorized students. */
export function StudentDashboardPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const q = useQuery({ queryKey: ["students", "overview"], queryFn: async () => unwrap(await api.GET("/api/v1/students/overview")) });
  const k = q.data;
  const loading = q.isPending;
  const n = (v: number | undefined) => formatNumber(v ?? 0);
  return (
    <>
      <PageHeader title="Students" description="Student records of the part of the organization you can see."
        actions={<>
          <ButtonLink to="/students/list">All students</ButtonLink>
          {access.hasAnywhere("student.manage") && <ButtonLink to="/students/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New student</ButtonLink>}
        </>} />
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card> : (
        <>
          <section aria-label="Student indicators" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard testId="kpi-total" icon={Users} label="Total students" value={n(k?.total)} secondary={`${n(k?.prospective)} prospective`} to="/students/list" loading={loading} />
            <KpiCard testId="kpi-active" icon={UserCheck} label="Active (enrolled or active)" value={n(k?.active)} to="/students/list?status=ACTIVE" loading={loading} />
            <KpiCard testId="kpi-new" icon={UserPlus} label="Newly admitted (30 days)" value={n(k?.newlyAdmitted)} to="/students/list?status=ADMITTED" loading={loading} />
            <KpiCard testId="kpi-suspended" icon={Pause} label="Suspended" value={n(k?.suspended)} to="/students/list?status=SUSPENDED" loading={loading} />
            <KpiCard testId="kpi-withdrawn" icon={UserMinus} label="Withdrawn" value={n(k?.withdrawn)} to="/students/list?status=WITHDRAWN" loading={loading} />
            <KpiCard testId="kpi-graduated" icon={GraduationCap} label="Graduated and alumni" value={n(k?.graduated)} to="/students/list?status=GRADUATED" loading={loading} />
            <KpiCard testId="kpi-documents" icon={FileClock} label="Documents awaiting malware scan" value={n(k?.documentsAwaitingScan)}
              secondary="Quarantined until scanned clean" loading={loading} />
          </section>
          <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
            <Distribution title={`By ${labels.singular("PROGRAM").toLowerCase()}`} rows={k?.byProgram} loading={loading} filter="programId" />
            <Distribution title={`By ${labels.singular("COURSE").toLowerCase()}`} rows={k?.byCourse} loading={loading} filter="courseId" />
            <Distribution title={`By ${labels.singular("BATCH").toLowerCase()}`} rows={k?.byBatch} loading={loading} filter="batchId" />
            <Distribution title="By academic year" rows={k?.byAcademicYear} loading={loading} filter="academicYearId" />
          </div>
        </>
      )}
    </>
  );
}

/** Current enrolments of enrolled, active and suspended students (top 10). */
function Distribution({ title, rows, loading, filter }: { title: string; rows: StudentGroupCount[] | undefined; loading: boolean; filter: string }) {
  const list = rows ?? [];
  const max = Math.max(1, ...list.map((r) => r.count ?? 0));
  return (
    <Card>
      <CardHeader title={title} description="Current enrolments (enrolled, active and suspended students)" />
      {loading ? <div className="h-40 animate-pulse p-6" /> : list.length === 0 ? (
        <EmptyState title="No enrolments yet" description="Students appear here once they are enrolled in a batch." />
      ) : (
        <ul className="space-y-4 p-5 sm:p-6">
          {list.map((r) => (
            <li key={r.id}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <Link to={`/students/list?${filter}=${r.id}`} className="min-w-0 truncate font-medium text-fg hover:underline">{r.label}</Link>
                <span className="tabular shrink-0 font-semibold text-fg">{formatNumber(r.count)}</span>
              </div>
              <div aria-hidden className="mt-1.5 h-1.5 rounded-full bg-border">
                <div className="h-1.5 rounded-full bg-primary" style={{ width: `${((r.count ?? 0) / max) * 100}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

export default StudentDashboardPage;
