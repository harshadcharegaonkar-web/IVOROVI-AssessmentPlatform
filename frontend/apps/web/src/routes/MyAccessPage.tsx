import { Card, CardBody, CardHeader, DataTable, PageHeader, type Column } from "@ivorovi/ui";
import { useOrgTree } from "../session/useOrgTree";
import { humanize } from "../lib/format";
import { useSession } from "../session/SessionProvider";

type GrantRow = { role: string; permission: string; scope: string; key: string };

export function MyAccessPage() {
  const { me } = useSession();
  const tree = useOrgTree();
  const names = new Map((tree.data ?? []).map((i) => [i.id, i.name]));
  const rows: GrantRow[] = (me.grants ?? []).map((g, i) => ({
    key: `${i}`,
    role: humanize(g.role),
    permission: g.permission ?? "",
    scope: g.scopeType === "TENANT" ? "Whole organization" : `${names.get(g.scopeId ?? "") ?? g.scopeId} (and its sub-units)`,
  }));
  const columns: Column<GrantRow>[] = [
    { key: "role", header: "Role", cell: (r) => r.role },
    { key: "permission", header: "Permission", cell: (r) => <code className="text-xs">{r.permission}</code> },
    { key: "scope", header: "Scope", cell: (r) => r.scope },
  ];
  return (
    <>
      <PageHeader title="My access" description="Your account and the permissions granted to you." />
      <div className="space-y-6">
        <Card>
          <CardHeader title="Account" />
          <CardBody>
            <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-3">
              <div><dt className="text-muted">Name</dt><dd className="font-medium">{me.displayName}</dd></div>
              <div><dt className="text-muted">Email</dt><dd className="font-medium break-all">{me.email}</dd></div>
              <div><dt className="text-muted">Organization</dt><dd className="font-medium">{me.tenant?.name}</dd></div>
            </dl>
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Permissions" description="Granted through your role assignments" />
          <DataTable caption="Permissions" columns={columns} rows={rows} rowKey={(r) => r.key}
            empty={{ title: "No permissions", description: "Your account has no active role assignments. Contact your administrator." }} />
        </Card>
      </div>
    </>
  );
}
export default MyAccessPage;
