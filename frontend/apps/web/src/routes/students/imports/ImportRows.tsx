import { unwrap, type StudentImportJob, type StudentImportRow } from "@ivorovi/api-client";
import { Badge, DataTable, Link, TabPanel, Tabs, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { useApi } from "../../../api/ApiProvider";
import { MATCH_TEXT, OUTCOME_TEXT, ROW_STATUS_TEXT } from "./importModel";

export type RowFilter = "ALL" | "ISSUES" | "ERRORS" | "WARNINGS" | "DUPLICATES" | "DECISIONS" | "UNRESOLVED" | "CONFLICTS" | "VALID" | "FAILED" | "IMPORTED" | "SKIPPED";

/** Paged rows of a job with server-side filters. */
export function useImportRows(job: StudentImportJob, filter: RowFilter, page: number, size = 25) {
  const api = useApi();
  return useQuery({
    queryKey: ["student-imports", "rows", job.id, job.version, filter, page], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/{id}/rows", { params: { path: { id: job.id! }, query: { filter, page, size } } })),
  });
}

/** Issues of one row with row and column references. */
export function IssueList({ row }: { row: StudentImportRow }) {
  const issues = row.issues ?? [];
  return (
    <ul className="space-y-1.5">
      {issues.map((i, n) => (
        <li key={n} className="text-sm">
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone={i.severity === "ERROR" ? "danger" : "warning"}>{i.severity === "ERROR" ? "Error" : "Warning"}</Badge>
            {i.column && <span className="font-medium text-fg">{i.column}{i.columnNo ? ` (column ${i.columnNo})` : ""}</span>}
          </span>
          <span className="mt-0.5 block text-fg">{i.message}</span>
          {i.hint && <span className="block text-xs text-muted">{i.hint}</span>}
        </li>
      ))}
      {row.matchReason && issues.length === 0 && <li className="text-sm text-fg">{row.matchReason}</li>}
    </ul>
  );
}

export function RowStatusBadge({ row }: { row: StudentImportRow }) {
  const t = ROW_STATUS_TEXT[row.status ?? ""] ?? { label: row.status ?? "", tone: "neutral" as const };
  return <Badge tone={t.tone}>{t.label}</Badge>;
}

export function MatchBadge({ category }: { category: string | undefined }) {
  if (!category) return null;
  const t = MATCH_TEXT[category] ?? { label: category, tone: "neutral" as const };
  return <Badge tone={t.tone}>{t.label}</Badge>;
}

export function OutcomeBadge({ outcome }: { outcome: string | undefined }) {
  const t = OUTCOME_TEXT[outcome ?? ""] ?? { label: outcome ?? "", tone: "neutral" as const };
  return <Badge tone={t.tone}>{t.label}</Badge>;
}

/** Row identity cell: row number, name, student ID. */
export function RowIdentity({ row }: { row: StudentImportRow }) {
  return (
    <span className="block min-w-0">
      <span className="block text-xs font-medium uppercase tracking-wide text-muted">Row {row.rowNo}</span>
      <span className="block truncate font-medium text-fg">{row.displayName ?? "(no name)"}</span>
      <span className="block truncate text-xs text-muted">{[row.studentNo, row.registrationNo].filter(Boolean).join(" · ") || "No student ID"}</span>
      {row.studentId && <Link to={`/students/${row.studentId}`} className="text-xs hover:underline">Open profile</Link>}
    </span>
  );
}

/** Filter tabs + paged table of rows. */
export function RowsPanel({ job, tabs, initial, columns, caption, empty, header }: {
  job: StudentImportJob; tabs: { value: RowFilter; label: string; count?: number | undefined }[]; initial: RowFilter; columns: Column<StudentImportRow>[];
  caption: string; empty: string; header?: ReactNode;
}) {
  const [filter, setFilter] = useState<RowFilter>(initial);
  const [page, setPage] = useState(0);
  const q = useImportRows(job, filter, page);
  return (
    <Tabs label={caption} value={filter} onValueChange={(v) => { setFilter(v as RowFilter); setPage(0); }} tabs={tabs}>
      <TabPanel value={filter}>
      {header}
      <DataTable caption={caption} columns={columns} rows={q.data?.items} rowKey={(r) => String(r.rowNo)} loading={q.isPending}
        error={q.isError ? { message: "Rows could not be loaded." } : null} onRetry={() => void q.refetch()}
        page={q.data ? { page, totalPages: q.data.totalPages ?? 0, totalElements: q.data.totalElements ?? 0, onPageChange: setPage } : undefined}
        empty={{ title: empty }} />
      </TabPanel>
    </Tabs>
  );
}
