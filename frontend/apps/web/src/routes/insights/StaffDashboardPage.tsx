import { unwrap } from "@ivorovi/api-client";
import { ButtonLink, Card, ErrorState, KpiCard, PageHeader } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { BadgeCheck, CalendarOff, FileText, Link2, UserCheck, UserMinus, UserX, Users } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useInstitutionContext } from "../../session/InstitutionContext";
import { useSession } from "../../session/SessionProvider";
import { BarList, kpi, SectionTitle } from "./Charts";

/**
 * Staff dashboard: headcount, distribution and the faculty assignment summary. Only facts recorded in the system are
 * shown; no workload or performance measure is derived (the data model has none yet).
 */
export function StaffDashboardPage() {
  const api = useApi();
  const { access } = useSession();
  const { selectedId } = useInstitutionContext();
  const q = useQuery({ queryKey: ["insights", "staff", selectedId], queryFn: async () =>
    unwrap(await api.GET("/api/v1/insights/staff", { params: { query: selectedId ? { orgUnitId: selectedId } : {} } })) });
  const d = q.data;
  const loading = q.isPending;
  const a = d?.assignments;
  return (
    <>
      <PageHeader title="Staff dashboard" description="Faculty and staff in your scope. Teaching workload is not measured yet, so it is not shown."
        actions={<>
          <ButtonLink to="/people/staff">All staff</ButtonLink>
          {access.hasAnywhere("report.read") && <ButtonLink to="/reports/staff"><FileText aria-hidden className="h-4 w-4" /> Staff report</ButtonLink>}
        </>} />
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card> : (
        <>
          <section aria-label="Staff indicators" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard testId="kpi-staff-total" icon={Users} label="Total staff" value={kpi(d?.total)} to="/people/staff" loading={loading} />
            <KpiCard testId="kpi-staff-active" icon={UserCheck} label="Active" value={kpi(d?.active)} to="/people/staff?status=ACTIVE" loading={loading} />
            <KpiCard testId="kpi-staff-leave" icon={CalendarOff} label="On leave" value={kpi(d?.onLeave)} to="/people/staff?status=ON_LEAVE" loading={loading} />
            <KpiCard testId="kpi-staff-separated" icon={UserMinus} label="Separated" value={kpi(d?.separated)} to="/people/staff?status=SEPARATED" loading={loading} />
            <KpiCard testId="kpi-staff-linked" icon={Link2} label="With a sign-in account" value={kpi(d?.linkedAccounts)} loading={loading} />
          </section>
          <SectionTitle>Faculty assignment summary</SectionTitle>
          <section aria-label="Faculty assignment summary" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard icon={BadgeCheck} label="Active faculty" value={kpi(a?.facultyActive)} loading={loading} />
            <KpiCard icon={UserCheck} label="Faculty teaching now" value={kpi(a?.facultyTeaching)} secondary={`${kpi(a?.activeAssignments)} assignments · ${kpi(a?.primaryAssignments)} primary`} loading={loading} />
            <KpiCard testId="kpi-faculty-unassigned" icon={UserX} label="Faculty without a teaching assignment" value={kpi(a?.facultyWithoutAssignment)} loading={loading} />
          </section>
          <SectionTitle>Distribution of active staff</SectionTitle>
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <BarList title="By category" rows={d?.byCategory} loading={loading} />
            <BarList title="By employment type" rows={d?.byEmploymentType} loading={loading} />
            <BarList title="By department" rows={d?.byDepartment} loading={loading} href={(r) => `/organization/units/${r.id}`} emptyTitle="No department assignments" />
            <BarList title="By campus" rows={d?.byCampus} loading={loading} href={(r) => `/organization/units/${r.id}`} emptyTitle="No campus assignments" />
          </div>
        </>
      )}
    </>
  );
}

export default StaffDashboardPage;
