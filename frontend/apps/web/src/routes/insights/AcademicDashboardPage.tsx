import { unwrap } from "@ivorovi/api-client";
import { ButtonLink, Card, ErrorState, KpiCard, PageHeader } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { BookOpen, FileText, UserCheck, UserX, Users } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useInstitutionContext } from "../../session/InstitutionContext";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { BarList, kpi, SectionTitle } from "./Charts";

/** Academic dashboard: structure distributions, enrolment by year and semester, faculty assignment coverage. */
export function AcademicDashboardPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const { selectedId } = useInstitutionContext();
  const q = useQuery({ queryKey: ["insights", "academic", selectedId], queryFn: async () =>
    unwrap(await api.GET("/api/v1/insights/academic", { params: { query: selectedId ? { orgUnitId: selectedId } : {} } })) });
  const d = q.data;
  const loading = q.isPending;
  const f = d?.faculty;
  return (
    <>
      <PageHeader title="Academic dashboard" description="Programs, courses, subjects, batches and teaching coverage in your scope."
        actions={access.hasAnywhere("report.read") ? <ButtonLink to="/reports/batches"><FileText aria-hidden className="h-4 w-4" /> Batch report</ButtonLink> : undefined} />
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card> : (
        <>
          <SectionTitle>Faculty assignment overview</SectionTitle>
          <section aria-label="Faculty assignment overview" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard testId="kpi-offerings" icon={BookOpen} label="Planned and active subject offerings" value={kpi(f?.offerings)} to="/academics/offerings" loading={loading} />
            <KpiCard testId="kpi-with-faculty" icon={UserCheck} label="Offerings with faculty" value={kpi(f?.offeringsWithFaculty)} loading={loading}
              secondary={f && f.offerings ? `${Math.round(((f.offeringsWithFaculty ?? 0) / f.offerings) * 100)}% covered` : undefined} />
            <KpiCard testId="kpi-without-faculty" icon={UserX} label="Offerings without faculty" value={kpi(f?.offeringsWithoutFaculty)} loading={loading} />
            <KpiCard testId="kpi-teaching-staff" icon={Users} label="Staff teaching" value={kpi(f?.staffTeaching)} secondary={`${kpi(f?.activeAssignments)} active assignments`} loading={loading} />
          </section>
          <SectionTitle>Structure</SectionTitle>
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <BarList title={`${labels.plural("PROGRAM")} by level`} rows={d?.programsByLevel} loading={loading} />
            <BarList title={`${labels.plural("PROGRAM")} by institution`} rows={d?.programsByInstitution} loading={loading} href={(r) => `/organization/units/${r.id}`} />
            <BarList title={`${labels.plural("COURSE")} per ${labels.singular("PROGRAM").toLowerCase()}`} rows={d?.coursesByProgram} loading={loading}
              href={(r) => `/academics/programs/${r.id}`} />
            <BarList title={`${labels.plural("SUBJECT")} by category`} rows={d?.subjectsByCategory} loading={loading} />
            <BarList title={`${labels.plural("BATCH")} by status`} rows={d?.batchesByStatus} loading={loading} />
            <BarList title={`Planned and active ${labels.plural("BATCH").toLowerCase()} by academic year`} rows={d?.batchesByAcademicYear} loading={loading}
              href={(r) => `/academics/calendar/${r.id}`} />
          </div>
          {(loading || d?.enrolmentByAcademicYear) && (
            <>
              <SectionTitle>Enrolment</SectionTitle>
              <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                <BarList title="Current enrolment by academic year" rows={d?.enrolmentByAcademicYear} loading={loading} emptyTitle="No enrolments yet" />
                <BarList title={`Current enrolment by ${labels.singular("TERM").toLowerCase()}`} rows={d?.enrolmentBySemester} loading={loading} emptyTitle="No enrolments yet" />
              </div>
            </>
          )}
          <SectionTitle>Offerings without faculty</SectionTitle>
          <BarList title="By department" description="Planned or active offerings that have no current faculty assignment" rows={f?.withoutFacultyByDepartment}
            loading={loading} emptyTitle="Every offering has faculty" href={(r) => `/organization/units/${r.id}`} />
        </>
      )}
    </>
  );
}

export default AcademicDashboardPage;
