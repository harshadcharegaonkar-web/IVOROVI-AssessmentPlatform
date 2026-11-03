import { ApiError, unwrap, type OrgAssignment } from "@ivorovi/api-client";
import { Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, ErrorState, Link, PageHeader, Skeleton, TabPanel, Tabs, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, Link2, Pencil, Unlink } from "lucide-react";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { invalidatePeople, OrgAssignmentDialog } from "./PeopleDialogs";
import { LinkUserDialog, StaffFormDialog } from "./StaffDialogs";
import { AssignmentTable, RemoveAssignmentDialog } from "./UserDetailPage";
import { ACCOUNT_STATUS, EMPLOYMENT_STATUS, EMPLOYMENT_TYPES, STAFF_CATEGORIES, type AccountStatus, type EmploymentStatus, type EmploymentType, type StaffCategory } from "./peopleModel";

type Dialog = null | "edit" | "link" | "unlink" | "add-assignment" | { remove: OrgAssignment };

export function StaffDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [dialog, setDialog] = useState<Dialog>(null);
  const q = useQuery({ queryKey: ["staff-profile", id], queryFn: async () => unwrap(await api.GET("/api/v1/staff/{id}", { params: { path: { id } } })) });
  const unlink = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/staff/{id}/unlink", { params: { path: { id } } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", "User account unlinked"); setDialog(null); },
    onError: (e) => { toast.show("danger", "Not unlinked", errorInfo(e).message); setDialog(null); },
  });
  const primary = useMutation({
    mutationFn: async (a: OrgAssignment) => unwrap(await api.POST("/api/v1/staff/{id}/org-assignments/{assignmentId}/primary", { params: { path: { id, assignmentId: a.id! } } })),
    onSuccess: () => { invalidatePeople(qc); toast.show("success", "Primary assignment changed"); },
    onError: (e) => toast.show("danger", "Not changed", errorInfo(e).message),
  });

  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    if (status === 404) return <ErrorState title="Staff profile not found" message="It may not exist or may belong to another organization." />;
    if (status === 403) return <ErrorState title="Access denied" message="This staff profile is outside the part of the organization you can access." />;
    return <ErrorState title="Could not load this profile" message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  }
  const detail = q.data;
  const s = detail.staff!;
  const canManage = !!detail.canManage;
  const emp = EMPLOYMENT_STATUS[s.employmentStatus as EmploymentStatus];

  return (
    <>
      <Link to="/people/staff" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg xl:hidden">
        <ChevronLeft aria-hidden className="h-4 w-4" /> Staff
      </Link>
      <PageHeader title={s.displayName ?? ""}
        description={<span className="inline-flex flex-wrap items-center gap-2"><code>{s.staffNo}</code><Badge tone="info">{STAFF_CATEGORIES[s.category as StaffCategory]}</Badge><Badge tone={emp.tone}>{emp.label}</Badge></span>}
        actions={canManage ? <>
          {s.userId ? <Button variant="secondary" onClick={() => setDialog("unlink")}><Unlink aria-hidden className="h-4 w-4" /> Unlink account</Button>
            : access.hasAnywhere("user.manage") && <Button variant="secondary" onClick={() => setDialog("link")}><Link2 aria-hidden className="h-4 w-4" /> Link user account</Button>}
          <Button onClick={() => setDialog("edit")}><Pencil aria-hidden className="h-4 w-4" /> Edit</Button>
        </> : undefined} />

      <Tabs label="Staff sections" value={tab} onValueChange={(v) => setParams(v === "overview" ? {} : { tab: v }, { replace: true })}
        tabs={[{ value: "overview", label: "Overview" }, { value: "assignments", label: "Organization", count: (detail.assignments ?? []).length }]}>
        <TabPanel value="overview">
          <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <CardHeader title="Profile" />
              <CardBody>
                <dl className="grid grid-cols-1 gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
                  <Item label="Staff ID"><code>{s.staffNo}</code></Item>
                  <Item label="Category">{STAFF_CATEGORIES[s.category as StaffCategory]}</Item>
                  <Item label="Designation">{s.designation ?? "—"}</Item>
                  <Item label="Primary unit">{s.primaryUnitName ?? "—"}</Item>
                  <Item label="Email"><span className="break-all">{s.email ?? "—"}</span></Item>
                  <Item label="Phone">{s.phone ?? "—"}</Item>
                  <Item label="Employment status">{emp.label}</Item>
                  <Item label="Employment type">{s.employmentType ? EMPLOYMENT_TYPES[s.employmentType as EmploymentType] : "—"}</Item>
                  <Item label="Joining date">{s.joinedOn ?? "—"}</Item>
                  <Item label="Last changed">{formatDateTime(s.updatedAt)}</Item>
                </dl>
              </CardBody>
            </Card>
            <Card>
              <CardHeader title="Login account" />
              <CardBody className="space-y-3 text-sm">
                {s.userId ? (
                  <>
                    <p><Link to={`/people/users/${s.userId}`} className="font-medium text-primary hover:underline">{s.userEmail}</Link></p>
                    <Badge tone={ACCOUNT_STATUS[s.userStatus as AccountStatus].tone}>{ACCOUNT_STATUS[s.userStatus as AccountStatus].label}</Badge>
                    <p className="text-muted">Roles and permissions are managed on the user account.</p>
                  </>
                ) : (
                  <p className="text-muted">This person has no login. Create and invite a user, then link the account here when they need access.</p>
                )}
              </CardBody>
            </Card>
          </div>
        </TabPanel>
        <TabPanel value="assignments">
          <AssignmentTable rows={detail.assignments} loading={false} error={null} onRetry={() => undefined} canManage={canManage}
            onAdd={() => setDialog("add-assignment")} onRemove={(a) => setDialog({ remove: a })} onPrimary={(a) => primary.mutate(a)} />
        </TabPanel>
      </Tabs>

      {dialog === "edit" && <StaffFormDialog open onOpenChange={(o) => !o && setDialog(null)} existing={detail} />}
      {dialog === "link" && <LinkUserDialog open onOpenChange={(o) => !o && setDialog(null)} staffId={id} suggestedEmail={s.email ?? undefined} />}
      {dialog === "add-assignment" && <OrgAssignmentDialog open onOpenChange={(o) => !o && setDialog(null)} subject="staff" id={id} />}
      {dialog && typeof dialog === "object" && <RemoveAssignmentDialog subject="staff" id={id} assignment={dialog.remove} onClose={() => setDialog(null)} />}
      {dialog === "unlink" && (
        <ConfirmDialog open onOpenChange={(o) => !o && setDialog(null)} title="Unlink the user account?" tone="danger" confirmLabel="Unlink"
          busy={unlink.isPending} onConfirm={() => unlink.mutate()}
          description={<p>The login stays active with its roles; it is no longer associated with this staff profile.</p>} />
      )}
    </>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-muted">{label}</dt><dd className="mt-1 font-medium text-fg">{children}</dd></div>;
}

export default StaffDetailPage;
