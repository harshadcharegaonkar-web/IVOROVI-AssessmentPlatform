import { unwrap, type StudentImportJob, type StudentImportRow } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, CardHeader, Link, Menu, MenuItem, useToast, type Column } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { ChevronDown, ShieldAlert } from "lucide-react";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { DECISION_TEXT, MATCH_TEXT } from "./importModel";
import { ImportJobFrame, stepPath, useRefreshImports } from "./ImportShared";
import { MatchBadge, RowIdentity, RowsPanel } from "./ImportRows";

type Decision = "CREATE_NEW" | "ADD_TO_EXISTING" | "SKIP";

/** Duplicate resolution: every ambiguous match needs an authorized decision; students are never merged. */
export function ImportDuplicatesPage() {
  return (
    <ImportJobFrame step="duplicates" title="Resolve duplicates"
      description="Rows that may describe a student who already exists, or who appears twice in the file. Nothing is merged automatically.">
      {(job) => <Duplicates job={job} />}
    </ImportJobFrame>
  );
}

function Duplicates({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const editable = !!job.can?.decide;
  const c = job.counts ?? {};
  const decide = useMutation({
    mutationFn: async (v: { rows?: number[]; category?: string; decision: Decision }) => unwrap(await api.POST("/api/v1/student-imports/{id}/decisions", { params: { path: { id: job.id! } },
        body: { ...(v.rows ? { rows: v.rows } : {}), ...(v.category ? { category: v.category as never } : {}), decision: v.decision, version: job.version! } })),
    onSuccess: () => { refresh(); toast.show("success", "Decision recorded"); },
    onError: (e) => toast.show("danger", "Decision not recorded", errorInfo(e).message),
  });
  const columns: Column<StudentImportRow>[] = [
    { key: "row", header: "Row in the file", cell: (r) => <RowIdentity row={r} /> },
    { key: "match", header: "Match", cell: (r) => (
      <span className="block min-w-0 space-y-1">
        <MatchBadge category={r.matchCategory} />
        <span className="block text-sm text-fg">{r.matchReason}</span>
        {r.matchStudentId && r.matchStudentNo && <Link to={`/students/${r.matchStudentId}`} className="text-xs hover:underline">Open {r.matchStudentNo}</Link>}
        {r.matchRowNo && <span className="block text-xs text-muted">Compare with row {r.matchRowNo}</span>}
      </span>) },
    { key: "decision", header: "Decision", cell: (r) => r.allowedDecisions?.length ? (
      <span className="block min-w-44 space-y-1">
        {r.decision ? <Badge tone={r.decision === "SKIP" ? "neutral" : "info"}>{DECISION_TEXT[r.decision]}</Badge> : <Badge tone="warning">Decision needed</Badge>}
        {r.decidedByName && <span className="block text-xs text-muted">by {r.decidedByName}</span>}
        {editable && (
          <Menu trigger={<Button size="sm" variant="secondary" aria-label={`Decide for row ${r.rowNo}`}>{r.decision ? "Change" : "Decide"} <ChevronDown aria-hidden className="h-3.5 w-3.5" /></Button>}>
            {r.allowedDecisions.map((d) => <MenuItem key={d} onSelect={() => decide.mutate({ rows: [r.rowNo!], decision: d as Decision })}>{DECISION_TEXT[d]}</MenuItem>)}
          </Menu>
        )}
      </span>) : <span className="text-sm text-muted">{r.status === "DUPLICATE" ? "Skipped automatically" : "Not imported (conflict)"}</span> },
  ];
  const categories = ["EXISTING_NEW_ENROLMENT", "POTENTIAL_DUPLICATE", "IN_FILE_POTENTIAL"];
  return (
    <div className="space-y-6">
      {(c.unresolved ?? 0) > 0 ? (
        <Alert tone="warning" title={`${c.unresolved} row(s) need a decision`}>
          <span className="flex gap-2"><ShieldAlert aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
            Decide for each possible duplicate whether it is a new student, adds records to the existing student, or is skipped. Profiles are never overwritten or merged.</span>
        </Alert>
      ) : <Alert tone="success" title="All decisions recorded">Continue to the preview to review what will be imported.</Alert>}
      {editable && (c.potentialDuplicates ?? 0) > 0 && (
        <Card>
          <CardHeader title="Decide for a whole category" description="Applies to every row of the category, including rows already decided." />
          <div className="flex flex-wrap gap-3 px-5 pb-5 sm:px-6">
            {categories.map((cat) => (
              <Menu key={cat} trigger={<Button size="sm">{MATCH_TEXT[cat]!.label} <ChevronDown aria-hidden className="h-3.5 w-3.5" /></Button>}>
                {(cat === "EXISTING_NEW_ENROLMENT" ? ["ADD_TO_EXISTING", "SKIP"] : ["CREATE_NEW", "SKIP"]).map((d) => (
                  <MenuItem key={d} onSelect={() => decide.mutate({ category: cat, decision: d as Decision })}>{DECISION_TEXT[d]}</MenuItem>
                ))}
              </Menu>
            ))}
          </div>
        </Card>
      )}
      <Card>
        <div className="px-5 pt-4 sm:px-6">
          <RowsPanel job={job} initial="DECISIONS" caption="Duplicate rows" empty="No rows in this view"
            tabs={[{ value: "UNRESOLVED", label: "Needs decision", count: c.unresolved }, { value: "DECISIONS", label: "All possible duplicates", count: c.potentialDuplicates },
              { value: "DUPLICATES", label: "All matches" }, { value: "CONFLICTS", label: "Conflicts", count: c.conflicts }]}
            columns={columns} />
        </div>
      </Card>
      <div className="flex flex-wrap justify-end gap-3">
        <ButtonLink to={stepPath(job.id!, "validation")}>Back to validation</ButtonLink>
        <ButtonLink variant="primary" to={stepPath(job.id!, "preview")} aria-disabled={(c.unresolved ?? 0) > 0}>Continue to preview</ButtonLink>
      </div>
    </div>
  );
}

export default ImportDuplicatesPage;
