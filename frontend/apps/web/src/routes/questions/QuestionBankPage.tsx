import { unwrap, type QuestionSummary } from "@ivorovi/api-client";
import { Button, ButtonLink, Card, CardBody, DataTable, ErrorState, Field, Input, PageHeader, Pagination, Select, Skeleton, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { FileUp, Images, Pencil, Plus } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { BankDialog, policyText } from "./QuestionBanksPage";
import { STATE_LABEL, StateBadge, TYPE_LABEL } from "./QuestionShared";

/** One bank: search and filters over statements and options (never keys), taxonomy filters, and entry points. */
export function QuestionBankPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const { access } = useSession();
  const [qText, setQText] = useState("");
  const [state, setState] = useState("");
  const [type, setType] = useState("");
  const [subject, setSubject] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [tag, setTag] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(false);
  const bank = useQuery({ queryKey: ["question-bank", id], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{id}", { params: { path: { id } } })) });
  const tax = useQuery({ queryKey: ["question-taxonomy"], staleTime: 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/question-taxonomy")) });
  const query = { page, size: 25, ...(qText ? { q: qText } : {}), ...(state ? { state: state as "DRAFT" } : {}), ...(type ? { type } : {}),
    ...(subject ? { subjectId: subject } : {}), ...(difficulty ? { difficultyId: difficulty } : {}), ...(tag ? { tag } : {}) };
  const list = useQuery({ queryKey: ["questions", id, query], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/question-banks/{bankId}/questions", { params: { path: { bankId: id }, query } })) });
  if (bank.isError) return <ErrorState message={errorInfo(bank.error).message} onRetry={() => void bank.refetch()} />;
  if (bank.isPending) return <Skeleton className="h-64 w-full" />;
  const b = bank.data;
  const columns: Column<QuestionSummary>[] = [
    { key: "q", header: "Question", cell: (s) => <ButtonLink variant="ghost" className="h-auto whitespace-normal px-0 py-1 text-left font-medium" to={`/questions/items/${s.id}`}>{s.excerpt || "(empty)"}</ButtonLink> },
    { key: "type", header: "Type", cell: (s) => TYPE_LABEL[s.type ?? ""] ?? s.type, hideOnMobile: true },
    { key: "state", header: "State", cell: (s) => <span className="flex items-center gap-2"><StateBadge state={s.state} /><span className="text-xs text-muted">v{s.versionNo}</span></span> },
    { key: "subject", header: "Subject", cell: (s) => s.subjectName ?? "—", hideOnMobile: true },
    { key: "marks", header: "Marks", cell: (s) => `${s.marks}${Number(s.negativeMarks) > 0 ? ` / −${s.negativeMarks}` : ""}`, hideOnMobile: true },
    { key: "updated", header: "Updated", cell: (s) => `${formatRelative(s.updatedAt)} · ${s.authorName ?? ""}`, hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title={b.name ?? "Bank"} description={`${b.orgUnitName ?? "Whole organization"} · ${policyText(b.effectivePolicy)}`}
        actions={<div className="flex flex-wrap gap-2">
          {b.canAuthor && <ButtonLink variant="primary" to={`/questions/banks/${id}/new`}><Plus aria-hidden className="h-4 w-4" /> New question</ButtonLink>}
          {b.canAuthor && <ButtonLink variant="secondary" to={`/questions/banks/${id}/import`}><FileUp aria-hidden className="h-4 w-4" /> Import</ButtonLink>}
          <ButtonLink variant="secondary" to={`/questions/banks/${id}/assets`}><Images aria-hidden className="h-4 w-4" /> Images</ButtonLink>
          {b.canManage && access.hasAnywhere("question.bank.manage") && <Button variant="secondary" onClick={() => setEditing(true)}><Pencil aria-hidden className="h-4 w-4" /> Edit bank</Button>}
        </div>} />
      <Card className="mb-4">
        <CardBody className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <div className="sm:col-span-2"><Field label="Search statements and options">{(p) => <Input {...p} type="search" value={qText} onChange={(e) => { setQText(e.target.value); setPage(0); }} />}</Field></div>
          <Field label="State">{(p) => <Select {...p} value={state} onChange={(e) => { setState(e.target.value); setPage(0); }}><option value="">Any</option>
            {Object.entries(STATE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}</Field>
          <Field label="Type">{(p) => <Select {...p} value={type} onChange={(e) => { setType(e.target.value); setPage(0); }}><option value="">Any</option>
            {Object.entries(TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}</Field>
          <Field label="Subject">{(p) => <Select {...p} value={subject} onChange={(e) => { setSubject(e.target.value); setPage(0); }}><option value="">Any</option>
            {(tax.data?.subjects ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>}</Field>
          <Field label="Difficulty">{(p) => <Select {...p} value={difficulty} onChange={(e) => { setDifficulty(e.target.value); setPage(0); }}><option value="">Any</option>
            {(tax.data?.difficulties ?? []).map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}</Select>}</Field>
          <Field label="Tag">{(p) => <Input {...p} value={tag} onChange={(e) => { setTag(e.target.value); setPage(0); }} />}</Field>
        </CardBody>
      </Card>
      <Card>
        <DataTable caption="Questions" columns={columns} rows={list.data?.items} rowKey={(s) => s.id!} loading={list.isPending}
          error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
          empty={{ title: "No questions match", description: b.canAuthor ? "Create one or import a spreadsheet." : "Nothing here yet." }} />
        {(list.data?.total ?? 0) > 25 && <Pagination page={page} totalPages={Math.ceil((list.data?.total ?? 0) / 25)} totalElements={list.data?.total ?? 0} onPageChange={setPage} />}
      </Card>
      {editing && <BankDialog bank={b} onClose={() => setEditing(false)} />}
    </>
  );
}

export default QuestionBankPage;
