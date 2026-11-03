import { unwrap, type StudentDetail, type StudentHistoryEntry } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, ErrorState, Link, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { KeyRound } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useOrgLabels } from "../../session/useOrgLabels";
import { Facts } from "../academics/AcademicsShared";
import { formatDate } from "../academics/academicsModel";
import { statusText, titleCase } from "./studentModel";
import { StudentStatusBadge } from "./StudentShared";

export function OverviewTab({ detail, onCreateAccount }: { detail: StudentDetail; onCreateAccount: () => void }) {
  const labels = useOrgLabels();
  const s = detail.student!;
  const e = detail.currentEnrolment;
  const a = detail.admission;
  const acct = detail.account;
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <div className="space-y-6 xl:col-span-2">
        <Card><CardHeader title="Summary" /><CardBody>
          <Facts items={[
            { label: "Status", value: <span className="inline-flex items-center gap-2"><StudentStatusBadge status={s.status} /><span className="text-xs text-muted">since {formatDate(detail.statusEffectiveOn)}</span></span> },
            { label: "Student ID", value: <code>{s.studentNo}</code> },
            { label: "Registration number", value: s.registrationNo || "—" },
            { label: "Roll number", value: s.rollNo || "—" },
            { label: `${labels.singular("UNIVERSITY")} / ${labels.singular("COLLEGE").toLowerCase()}`, value: s.institutionName },
            { label: "Unit", value: s.departmentName },
          ]} />
        </CardBody></Card>
        <Card>
          <CardHeader title="Current enrolment" actions={<Link to="?tab=academic" className="text-sm text-primary underline-offset-2 hover:underline">Academic details</Link>} />
          <CardBody>
            {e ? <Facts items={[
              { label: labels.singular("PROGRAM"), value: e.programName }, { label: labels.singular("COURSE"), value: e.courseName },
              { label: labels.singular("BATCH"), value: `${e.batchName} (${e.batchCode})` }, { label: "Academic year", value: e.academicYearName },
              { label: labels.singular("TERM"), value: `${e.termNumber}${e.termName ? ` · ${e.termName}` : ""}` }, { label: "Enrolment status", value: titleCase(e.status) },
            ]} /> : <p className="text-sm text-muted">{a ? `Admitted (admission ${a.admissionNo}); not enrolled in a ${labels.singular("BATCH").toLowerCase()} yet.` : "No admission recorded yet."}</p>}
          </CardBody>
        </Card>
      </div>
      <div className="space-y-6">
        <Card>
          <CardHeader title="Sign-in account" />
          <CardBody className="space-y-3 text-sm">
            {acct ? (<>
              <p className="flex items-center gap-2"><KeyRound aria-hidden className="h-4 w-4 text-muted" /> {acct.email}</p>
              <p><Badge tone={acct.status === "ACTIVE" ? "success" : "info"}>{titleCase(acct.status)}</Badge>
                {acct.invitationStatus && <span className="ml-2 text-xs text-muted">Invitation {acct.invitationStatus.toLowerCase()}</span>}</p>
              <p className="text-xs text-muted">Student role only.</p>
            </>) : (<>
              <p className="text-muted">No sign-in account. Accounts are created only on request.</p>
              {detail.can?.manageAccount && s.active && <Button size="sm" variant="secondary" onClick={onCreateAccount}>Create sign-in account</Button>}
            </>)}
          </CardBody>
        </Card>
        <Card><CardHeader title="Records" /><CardBody>
          <Facts items={[
            { label: "Guardians", value: detail.personalVisible ? detail.guardianCount : "Restricted" },
            { label: "Documents", value: detail.can?.readDocuments ? `${detail.documentCount}${detail.pendingScanCount ? ` (${detail.pendingScanCount} awaiting scan)` : ""}` : "Restricted" },
            { label: "Profile created", value: formatDateTime(detail.createdAt) },
          ]} />
        </CardBody></Card>
      </div>
    </div>
  );
}

export function PersonalTab({ detail }: { detail: StudentDetail }) {
  const s = detail.student!;
  const p = detail.personal;
  return (
    <div className="space-y-6">
      <Card><CardHeader title="Name and identification" /><CardBody>
        <Facts items={[{ label: "First name", value: s.firstName }, { label: "Middle name", value: s.middleName || "—" }, { label: "Last name", value: s.lastName || "—" },
          { label: "Institutional email", value: detail.institutionalEmail || "—" }, { label: "Previous institution ID", value: detail.previousInstitutionRef || "—" }]} />
      </CardBody></Card>
      {detail.personalVisible && p ? (<>
        <Card><CardHeader title="Personal information" description="Stored encrypted." /><CardBody>
          <Facts items={[{ label: "Date of birth", value: formatDate(p.dateOfBirth) }, { label: "Gender", value: p.gender ? titleCase(p.gender) : "Not recorded" },
            { label: "Nationality", value: p.nationality || "—" }]} />
        </CardBody></Card>
        <Card><CardHeader title="Contact information" /><CardBody>
          <Facts items={[{ label: "Personal email", value: p.personalEmail || "—" }, { label: "Mobile", value: p.mobile || "—" }, { label: "Alternate number", value: p.alternatePhone || "—" },
            { label: "Current address", value: p.currentAddress ? <span className="whitespace-pre-line">{p.currentAddress}</span> : "—" },
            { label: "Permanent address", value: p.permanentAddress ? <span className="whitespace-pre-line">{p.permanentAddress}</span> : "—" }]} />
        </CardBody></Card>
      </>) : <Alert tone="info">Personal and contact details are restricted. Ask an administrator if you need access.</Alert>}
    </div>
  );
}

export function HistoryTab({ studentId }: { studentId: string }) {
  const api = useApi();
  const q = useQuery({ queryKey: ["students", "history", studentId], queryFn: async () => unwrap(await api.GET("/api/v1/students/{id}/history", { params: { path: { id: studentId } } })) });
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  const status = q.data.filter((h) => h.kind === "STATUS");
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
      <Card>
        <CardHeader title="Lifecycle history" description="Every status change with its effective date, reason and who made it." />
        <ol className="divide-y divide-border" data-testid="lifecycle-history">
          {status.map((h, i) => <Entry key={i} h={h} />)}
        </ol>
      </Card>
      <Card>
        <CardHeader title="Change log" description="Profile, admission, enrolment, guardian, document and account changes (no personal values)." />
        {q.data.filter((h) => h.kind !== "STATUS").length === 0 ? <CardBody><p className="text-sm text-muted">No changes yet.</p></CardBody> : (
          <ol className="divide-y divide-border">{q.data.filter((h) => h.kind !== "STATUS").map((h, i) => <Entry key={i} h={h} />)}</ol>
        )}
      </Card>
    </div>
  );
}

function Entry({ h }: { h: StudentHistoryEntry }) {
  return (
    <li className="px-5 py-3 text-sm sm:px-6">
      <p className="font-medium text-fg">{h.kind === "STATUS" ? <>{h.fromStatus ? `${statusText(h.fromStatus)} → ` : ""}{statusText(h.toStatus)}</> : h.summary}</p>
      {h.reason && <p className="mt-0.5 text-muted-strong">{h.reason}</p>}
      <p className="mt-0.5 text-xs text-muted">{formatDateTime(h.at)}{h.actorName ? ` · ${h.actorName}` : ""}{h.effectiveOn ? ` · effective ${formatDate(h.effectiveOn)}` : ""}</p>
    </li>
  );
}
