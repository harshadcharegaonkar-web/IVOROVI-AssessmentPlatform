import { unwrap, type AcademicTerm } from "@ivorovi/api-client";
import { Breadcrumbs, Button, Card, CardBody, CardHeader, DataTable, Menu, MenuItem, PageHeader, Skeleton, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { MoreHorizontal, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, Facts, StatusBadge } from "./AcademicsShared";
import { calendarTransitions, formatRange, statusLabel } from "./academicsModel";
import { CalendarStatusDialog, TermDialog, YearDialog } from "./CalendarDialogs";

type Dialog = { kind: "edit-year" } | { kind: "year-status"; to: "PLANNED" | "ACTIVE" | "COMPLETED" } | { kind: "term"; term?: AcademicTerm }
  | { kind: "term-status"; term: AcademicTerm; to: "PLANNED" | "ACTIVE" | "COMPLETED" } | null;

/** An academic year and its terms (semester management within the year). */
export function AcademicYearDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const [dialog, setDialog] = useState<Dialog>(null);
  const q = useQuery({ queryKey: ["academics", "year", id], queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-years/{id}", { params: { path: { id } } })) });
  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what="Academic year" onRetry={() => void q.refetch()} />;
  const y = q.data.year!;
  const terms = q.data.terms ?? [];
  const manage = !!y.canManage;
  const open = y.status !== "COMPLETED";
  const term = labels.singular("TERM");
  const columns: Column<AcademicTerm>[] = [
    { key: "name", header: term, cell: (t) => <span className="block min-w-0"><span className="block truncate font-medium">{t.name}</span><span className="block text-xs text-muted">No. {t.sequence}</span></span> },
    { key: "dates", header: "Dates", cell: (t) => formatRange(t.startDate, t.endDate) },
    { key: "registration", header: "Registration", hideOnMobile: true, cell: (t) => formatRange(t.registrationStart, t.registrationEnd) },
    { key: "exams", header: "Examinations", hideOnMobile: true, cell: (t) => formatRange(t.examStart, t.examEnd) },
    { key: "offerings", header: "Offerings", hideOnMobile: true, cell: (t) => <span className="tabular">{t.offeringCount}</span> },
    { key: "status", header: "Status", cell: (t) => <StatusBadge status={t.status} /> },
    ...(manage ? [{ key: "actions", header: "Actions", cell: (t: AcademicTerm) => (
      <Menu trigger={<Button size="sm" variant="ghost" aria-label={`Actions for ${t.name}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
        {t.status !== "COMPLETED" && <MenuItem onSelect={() => setDialog({ kind: "term", term: t })}><Pencil aria-hidden className="h-4 w-4" /> Edit</MenuItem>}
        {calendarTransitions(t.status).map((tr) => <MenuItem key={tr.to} onSelect={() => setDialog({ kind: "term-status", term: t, to: tr.to })}>Mark {statusLabel(tr.to).toLowerCase()}{tr.correction ? " (correction)" : ""}</MenuItem>)}
      </Menu>) }] satisfies Column<AcademicTerm>[] : []),
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: "Academic calendar", to: "/academics/calendar" }, { label: y.name ?? "" }]} /></div>
      <PageHeader title={y.name ?? ""} description={<span className="inline-flex flex-wrap items-center gap-2"><code>{y.code}</code><StatusBadge status={y.status} /></span>}
        actions={manage ? <>
          {open && <Button variant="secondary" onClick={() => setDialog({ kind: "edit-year" })}><Pencil aria-hidden className="h-4 w-4" /> Edit</Button>}
          {calendarTransitions(y.status).map((tr) => <Button key={tr.to} variant={tr.correction ? "ghost" : "secondary"} onClick={() => setDialog({ kind: "year-status", to: tr.to })}>
            {tr.to === "ACTIVE" ? (y.status === "COMPLETED" ? "Re-open" : "Activate") : tr.to === "COMPLETED" ? "Complete" : "Return to planned"}</Button>)}
        </> : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[{ label: "Applies to", value: y.ownerName ?? "Whole organization" }, { label: "Dates", value: formatRange(y.startDate, y.endDate) },
            { label: labels.plural("BATCH"), value: y.batchCount },
            { label: "Overlapping years", value: q.data.overlappingYearsAllowed ? "Allowed by System Settings" : "Not allowed" }]} />
        </CardBody></Card>
        <Card>
          <CardHeader title={labels.plural("TERM")} description={`Activate the year before its ${labels.plural("TERM").toLowerCase()}; complete the ${labels.plural("TERM").toLowerCase()} before the year.`}
            actions={manage && open ? <Button size="sm" onClick={() => setDialog({ kind: "term" })}><Plus aria-hidden className="h-4 w-4" /> Add {term.toLowerCase()}</Button> : undefined} />
          <DataTable caption={labels.plural("TERM")} columns={columns} rows={terms} rowKey={(t) => t.id!}
            empty={{ title: `No ${labels.plural("TERM").toLowerCase()} yet`, description: `Add ${labels.plural("TERM").toLowerCase()} with their registration and examination windows.` }} />
        </Card>
      </div>
      {dialog?.kind === "edit-year" && <YearDialog year={y} onClose={() => setDialog(null)} />}
      {dialog?.kind === "year-status" && <CalendarStatusDialog kind="year" item={y} to={dialog.to} onClose={() => setDialog(null)} />}
      {dialog?.kind === "term" && <TermDialog year={y} term={dialog.term} nextSequence={Math.max(0, ...terms.map((t) => t.sequence ?? 0)) + 1} onClose={() => setDialog(null)} />}
      {dialog?.kind === "term-status" && <CalendarStatusDialog kind="term" item={dialog.term} to={dialog.to} onClose={() => setDialog(null)} />}
    </>
  );
}

export default AcademicYearDetailPage;
