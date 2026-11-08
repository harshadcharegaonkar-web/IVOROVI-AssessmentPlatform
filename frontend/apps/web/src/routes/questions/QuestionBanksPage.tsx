import { unwrap, type QuestionBank } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, DataTable, Field, Input, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { QuestionTabs } from "./QuestionShared";

const USES: [string, string][] = [["ACADEMIC", "Academic assessment"], ["PRACTICE", "Practice"], ["PLACEMENT", "Placement"],
  ["ADMISSION_SCREENING", "Admission screening"], ["ADMISSION_EXAM", "Admission examination"]];

export function policyText(p?: { approvalPolicy?: string; requiredReviews?: number; approverDistinct?: boolean }): string {
  if (!p) return "";
  const reviews = `${p.requiredReviews ?? 1} review${(p.requiredReviews ?? 1) > 1 ? "s" : ""}`;
  return p.approvalPolicy === "HIGH_STAKES" ? `High-stakes · ${reviews} · separate approver` : `Standard · ${reviews}${p.approverDistinct ? " · separate approver" : ""}`;
}

/** Institution-scoped question banks the caller can read. */
export function QuestionBanksPage() {
  const api = useApi();
  const { access } = useSession();
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ["question-banks"], queryFn: async () => unwrap(await api.GET("/api/v1/question-banks")) });
  const columns: Column<QuestionBank>[] = [
    { key: "name", header: "Bank", cell: (b) => <ButtonLink variant="ghost" className="px-0 font-medium" to={`/questions/banks/${b.id}`}>{b.name}</ButtonLink> },
    { key: "unit", header: "Institution", cell: (b) => b.orgUnitName ?? "Whole organization", hideOnMobile: true },
    { key: "policy", header: "Approval policy", cell: (b) => <span className="text-sm">{policyText(b.effectivePolicy)}{b.pendingPolicyApprovalId && <Badge tone="warning" className="ml-2">Change pending</Badge>}</span> },
    { key: "count", header: "Approved / total", cell: (b) => `${formatNumber(b.counts?.approved ?? 0)} / ${formatNumber(b.counts?.total ?? 0)}`, hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Question bank" description="Institution-scoped banks of versioned questions with independent review and approval."
        actions={access.hasAnywhere("question.bank.manage") && <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New bank</Button>} />
      <QuestionTabs>
        <Card>
          <DataTable caption="Question banks" columns={columns} rows={q.data} rowKey={(b) => b.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No question bank yet", description: "A bank holds the questions of one institution (or the whole organization)." }} />
        </Card>
      </QuestionTabs>
      {creating && <BankDialog onClose={() => setCreating(false)} />}
    </>
  );
}

export function BankDialog({ bank, onClose }: { bank?: QuestionBank; onClose: () => void }) {
  const api = useApi();
  const { access } = useSession();
  const qc = useQueryClient();
  const [unit, setUnit] = useState(bank?.orgUnitId ?? "");
  const [code, setCode] = useState(bank?.code ?? "");
  const [name, setName] = useState(bank?.name ?? "");
  const [description, setDescription] = useState(bank?.description ?? "");
  const [uses, setUses] = useState<string[]>(bank?.intendedUses ?? ["ACADEMIC"]);
  const [policy, setPolicy] = useState(bank?.policy?.approvalPolicy ?? "STANDARD");
  const [reviews, setReviews] = useState(bank?.policy?.requiredReviews ?? 1);
  const [distinct, setDistinct] = useState(bank?.policy?.approverDistinct ?? false);
  const admission = uses.includes("ADMISSION_EXAM");
  const save = useMutation({
    mutationFn: async () => {
      const body = { orgUnitId: unit || undefined, code, name, description, intendedUses: uses as ("ACADEMIC")[],
        policy: { approvalPolicy: (admission ? "HIGH_STAKES" : policy) as "STANDARD", requiredReviews: reviews, approverDistinct: admission || policy === "HIGH_STAKES" || distinct },
        ...(bank ? { version: bank.version } : {}) };
      return bank ? unwrap(await api.PUT("/api/v1/question-banks/{id}", { params: { path: { id: bank.id! } }, body }))
        : unwrap(await api.POST("/api/v1/question-banks", { body }));
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["question-banks"] }); void qc.invalidateQueries({ queryKey: ["question-bank"] }); onClose(); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={bank ? "Edit bank" : "New question bank"}
      description="Institutions may require stricter approval than a bank, never weaker. Making a bank's policy weaker needs a second administrator's approval."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!name.trim() || !code.trim()} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        {!bank && <Field label="Institution">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}>{!access.has("question.bank.manage") && <option value="">Choose…</option>}<UnitOptions permission="question.bank.manage" /></Select>}</Field>}
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Code">{(p) => <Input {...p} value={code} maxLength={40} disabled={!!bank} onChange={(e) => setCode(e.target.value)} />}</Field>
          <div className="sm:col-span-2"><Field label="Name">{(p) => <Input {...p} value={name} maxLength={150} onChange={(e) => setName(e.target.value)} />}</Field></div>
        </div>
        <Field label="Description">{(p) => <TextArea {...p} rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
        <fieldset>
          <legend className="mb-1 text-sm font-medium">Intended uses</legend>
          <div className="flex flex-wrap gap-3">{USES.map(([v, l]) => (
            <label key={v} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={uses.includes(v)}
              onChange={(e) => setUses(e.target.checked ? [...uses, v] : uses.filter((u) => u !== v))} />{l}</label>))}</div>
        </fieldset>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Approval policy" hint={admission ? "Admission examination banks are always high-stakes." : "High-stakes: author, reviewer and approver are three different people."}>
            {(p) => <Select {...p} value={admission ? "HIGH_STAKES" : policy} disabled={admission} onChange={(e) => setPolicy(e.target.value)}>
              <option value="STANDARD">Standard</option><option value="HIGH_STAKES">High-stakes</option></Select>}
          </Field>
          <Field label="Required reviews">{(p) => <Select {...p} value={String(reviews)} onChange={(e) => setReviews(Number(e.target.value))}>
            <option value="1">1</option><option value="2">2</option><option value="3">3</option></Select>}</Field>
        </div>
        {policy === "STANDARD" && !admission && (
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={distinct} onChange={(e) => setDistinct(e.target.checked)} />
            Approver must be someone who did not review the question</label>)}
      </div>
    </Modal>
  );
}

export default QuestionBanksPage;
