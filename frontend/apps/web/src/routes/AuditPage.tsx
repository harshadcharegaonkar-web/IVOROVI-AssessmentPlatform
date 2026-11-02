import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type AuditEvent, type AuditVerification } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, DataTable, Field, Input, PageHeader, Select, useToast, type Column } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatDateTime } from "../lib/format";
import { auditFilterSchema, toQuery, type AuditFilterInput } from "./auditFilter";

const PAGE_SIZE = 25;

export function AuditPage() {
  const api = useApi();
  const toast = useToast();
  const [filter, setFilter] = useState<AuditFilterInput>({ action: "", outcome: "", from: "", to: "" });
  const [page, setPage] = useState(0);
  const [verification, setVerification] = useState<AuditVerification | null>(null);
  const form = useForm<AuditFilterInput>({ resolver: zodResolver(auditFilterSchema), defaultValues: filter });

  const query = useQuery({
    queryKey: ["audit", filter, page],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/audit-events", { params: { query: { ...toQuery(filter), page, size: PAGE_SIZE } } })),
  });

  const verify = useMutation({
    mutationFn: async () => unwrap(await api.GET("/api/v1/audit-events/verification")),
    onSuccess: setVerification,
    onError: (e) => toast.show("danger", "Verification failed", errorInfo(e).message),
  });

  const columns: Column<AuditEvent>[] = [
    { key: "occurredAt", header: "Time", cell: (e) => <span className="whitespace-nowrap">{formatDateTime(e.occurredAt)}</span> },
    { key: "action", header: "Action", cell: (e) => <code className="text-xs">{e.action}</code> },
    { key: "actor", header: "Actor", hideOnMobile: true, cell: (e) => (
      <span className="block max-w-48 truncate" title={e.actorSubject}>{e.actorName ?? (e.actorSubject === "system" ? "System" : e.actorSubject)}</span>
    ) },
    { key: "resource", header: "Resource", hideOnMobile: true, cell: (e) => e.resourceType ? <span className="text-xs">{e.resourceType}: {e.resourceId}</span> : "—" },
    { key: "outcome", header: "Outcome", cell: (e) => <Badge tone={e.outcome === "SUCCESS" ? "success" : e.outcome === "DENIED" ? "danger" : "warning"}>{e.outcome}</Badge> },
    { key: "details", header: "Details", hideOnMobile: true, cell: (e) => (
      <span className="line-clamp-2 max-w-md break-all text-xs text-muted" title={Object.entries(e.details ?? {}).map(([k, v]) => `${k}=${v}`).join("\n")}>
        {Object.entries(e.details ?? {}).map(([k, v]) => `${k}=${v}`).join(", ") || "—"}
      </span>
    ) },
  ];

  return (
    <>
      <PageHeader
        title="Audit log"
        description="Tamper-evident record of sensitive operations in your organization."
        actions={<Button variant="secondary" onClick={() => verify.mutate()} loading={verify.isPending}><ShieldCheck aria-hidden className="h-4 w-4" /> Verify integrity</Button>}
      />
      {verification && (
        <Alert className="mb-4" tone={verification.valid ? "success" : "danger"} title={verification.valid ? "Audit trail verified" : "Integrity check failed"}>
          {verification.valid
            ? `${verification.eventsChecked} events checked; the hash chain is intact.`
            : `The chain breaks at event #${verification.firstInvalidSeq}. Escalate to your security officer.`}
        </Alert>
      )}
      <Card>
        <form
          className="grid grid-cols-1 gap-3 border-b border-border px-4 py-4 sm:grid-cols-2 sm:px-6 lg:grid-cols-5 lg:items-end"
          onSubmit={form.handleSubmit((values) => { setPage(0); setFilter(values); })}
          noValidate
        >
          <Field label="Action" error={form.formState.errors.action?.message}>
            {(p) => <Input {...p} placeholder="e.g. feature.override.changed" {...form.register("action")} />}
          </Field>
          <Field label="Outcome">
            {(p) => (
              <Select {...p} {...form.register("outcome")}>
                <option value="">Any</option>
                <option value="SUCCESS">Success</option>
                <option value="DENIED">Denied</option>
                <option value="FAILURE">Failure</option>
              </Select>
            )}
          </Field>
          <Field label="From" error={form.formState.errors.from?.message}>{(p) => <Input {...p} type="datetime-local" {...form.register("from")} />}</Field>
          <Field label="To" error={form.formState.errors.to?.message}>{(p) => <Input {...p} type="datetime-local" {...form.register("to")} />}</Field>
          <div className="flex gap-2">
            <Button type="submit">Apply</Button>
            <Button variant="ghost" onClick={() => { const empty = { action: "", outcome: "", from: "", to: "" } as const; form.reset(empty); setPage(0); setFilter(empty); }}>Clear</Button>
          </div>
        </form>
        <DataTable
          caption="Audit events"
          columns={columns}
          rows={query.data?.items}
          rowKey={(e) => e.id!}
          loading={query.isPending}
          error={query.isError ? errorInfo(query.error) : null}
          onRetry={() => void query.refetch()}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: setPage } : undefined}
          empty={{ title: "No audit events match these filters" }}
        />
      </Card>
    </>
  );
}
export default AuditPage;
