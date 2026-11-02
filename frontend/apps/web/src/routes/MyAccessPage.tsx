import { unwrap } from "@ivorovi/api-client";
import { Card, CardBody, CardHeader, DataTable, PageHeader, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiProvider";
import { humanize } from "../lib/format";
import { useSession } from "../session/SessionProvider";

type GrantRow = { role: string; permission: string; scope: string; key: string };

export function MyAccessPage() {
  const api = useApi();
  const { me, access } = useSession();
  const institutions = useQuery({
    queryKey: ["institutions", "names"],
    enabled: access.hasAnywhere("institution.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { size: 100 } } })),
  });
  const names = new Map((institutions.data?.items ?? []).map((i) => [i.id, i.name]));
  const rows: GrantRow[] = (me.grants ?? []).map((g, i) => ({
    key: `${i}`,
    role: humanize(g.role),
    permission: g.permission ?? "",
    scope: g.scopeType === "TENANT" ? "Whole organization" : `Institution: ${names.get(g.scopeId) ?? g.scopeId}`,
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
            <dl className="grid gap-4 text-sm sm:grid-cols-3">
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
