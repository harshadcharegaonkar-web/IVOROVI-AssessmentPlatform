import { unwrap } from "@ivorovi/api-client";
import { ButtonLink, Card, CardHeader, DataTable, ErrorState, KpiCard, Link, PageHeader } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { BookOpen, CalendarDays, CalendarRange, GitBranch, GraduationCap, Layers, Network, UserCheck, Users } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useOrgLabels } from "../../session/useOrgLabels";

/** Academic overview: KPIs from the caller's authorized data, and offerings that still need faculty. */
export function AcademicOverviewPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const kpis = useQuery({ queryKey: ["academics", "overview"], queryFn: async () => unwrap(await api.GET("/api/v1/academics/overview")) });
  const pending = useQuery({ queryKey: ["academics", "offerings", { withoutFaculty: true, overview: true }],
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings", { params: { query: { withoutFaculty: true, size: 5, sort: "term", direction: "desc" } } })) });
  const k = kpis.data;
  const loading = kpis.isPending;
  const n = (v: number | undefined) => formatNumber(v ?? 0);
  return (
    <>
      <PageHeader title="Academics" description="Programs, curricula, the academic calendar, batches and who teaches what — for the part of the organization you can see."
        actions={<ButtonLink to="/academics/structure"><Network aria-hidden className="h-4 w-4" /> Structure tree</ButtonLink>} />
      {kpis.isError ? <Card><ErrorState message={errorInfo(kpis.error).message} onRetry={() => void kpis.refetch()} /></Card> : (
        <section aria-label="Academic indicators" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <KpiCard testId="kpi-programs" icon={GraduationCap} label={`Active ${labels.plural("PROGRAM").toLowerCase()}`} value={n(k?.programs)} to="/academics/programs" loading={loading} />
          <KpiCard testId="kpi-courses" icon={GitBranch} label={`Active ${labels.plural("COURSE").toLowerCase()}`} value={n(k?.courses)} to="/academics/courses" loading={loading} />
          <KpiCard testId="kpi-subjects" icon={BookOpen} label={`Active ${labels.plural("SUBJECT").toLowerCase()}`} value={n(k?.subjects)} to="/academics/subjects" loading={loading} />
          <KpiCard testId="kpi-years" icon={CalendarRange} label="Active academic years" value={n(k?.activeAcademicYears)} to="/academics/calendar" loading={loading} />
          <KpiCard testId="kpi-terms" icon={CalendarDays} label={`Active ${labels.plural("TERM").toLowerCase()}`} value={n(k?.activeTerms)} to="/academics/terms" loading={loading} />
          <KpiCard testId="kpi-batches" icon={Users} label={`Active ${labels.plural("BATCH").toLowerCase()}`} value={n(k?.activeBatches)} to="/academics/batches" loading={loading} />
          <KpiCard testId="kpi-offerings" icon={Layers} label="Subject offerings" value={n(k?.offerings)} secondary={`${n(k?.offeringsWithoutFaculty)} without faculty`} to="/academics/offerings" loading={loading} />
          <KpiCard testId="kpi-faculty" icon={UserCheck} label="Faculty assignments" value={n(k?.facultyAssignments)} secondary={`${n(k?.facultyTeaching)} staff teaching`} to="/academics/offerings" loading={loading} />
        </section>
      )}
      <Card className="mt-6">
        <CardHeader title="Offerings without faculty" description="Planned or active offerings nobody has been assigned to yet."
          actions={<Link to="/academics/offerings?unassigned=1" className="text-sm text-primary underline-offset-2 hover:underline">View all</Link>} />
        <DataTable caption="Offerings without faculty" rows={pending.data?.items} rowKey={(o) => o.id!} loading={pending.isPending}
          error={pending.isError ? errorInfo(pending.error) : null} onRetry={() => void pending.refetch()}
          columns={[
            { key: "subject", header: labels.singular("SUBJECT"), cell: (o) => <Link to={`/academics/offerings/${o.id}`} className="font-medium text-primary underline-offset-2 hover:underline">{o.subjectCode} · {o.subjectName}</Link> },
            { key: "batch", header: labels.singular("BATCH"), cell: (o) => o.batchCode },
            { key: "term", header: labels.singular("TERM"), hideOnMobile: true, cell: (o) => `${o.termName} · ${o.academicYearName}` },
          ]}
          empty={{ title: "Every offering has faculty", description: "Nothing to do here." }} />
      </Card>
    </>
  );
}

export default AcademicOverviewPage;
