import { unwrap } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Select } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { policyText } from "./QuestionBanksPage";
import { QuestionTabs } from "./QuestionShared";

/** Institution minimum approval policies (stricter only; weakening needs a second administrator) and image storage quotas. */
export function QuestionPoliciesPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const manage = access.hasAnywhere("question.bank.manage");
  const floors = useQuery({ queryKey: ["question-floors"], queryFn: async () => unwrap(await api.GET("/api/v1/question-policy-floors")) });
  const [unit, setUnit] = useState("");
  const [policy, setPolicy] = useState("STANDARD");
  const [reviews, setReviews] = useState(1);
  const [distinct, setDistinct] = useState(false);
  const [quotaUnit, setQuotaUnit] = useState("");
  const [quotaMb, setQuotaMb] = useState("2048");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/question-policy-floors/{orgUnitId}", { params: { path: { orgUnitId: unit } },
      body: { approvalPolicy: policy as "STANDARD", requiredReviews: reviews, approverDistinct: policy === "HIGH_STAKES" || distinct } })),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["question-floors"] }); void qc.invalidateQueries({ queryKey: ["question-banks"] }); },
  });
  const quota = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/question-asset-quotas", { params: { query: quotaUnit ? { orgUnitId: quotaUnit } : {} },
      body: { limitBytes: Math.round(Number(quotaMb) * 1024 * 1024) } })),
  });
  return (
    <>
      <PageHeader title="Question bank" description="Institutions can require stricter approval than their banks, never weaker. Making a policy weaker waits for a second administrator's approval." />
      <QuestionTabs>
        <div className="grid gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader title="Institution minimum approval policies" />
            <CardBody className="space-y-4">
              <ul className="space-y-1 text-sm">{(floors.data ?? []).map((f) => <li key={f.id}><span className="font-medium">{f.orgUnitName}</span> · {policyText(f.policy)}</li>)}
                {(floors.data ?? []).length === 0 && <li className="text-muted">No institution minimums set: every bank's own policy applies.</li>}</ul>
              {manage && <>
                {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
                {save.data && <Alert tone="success">Saved. If this made the policy weaker, it applies only after a second administrator approves it under Approvals.</Alert>}
                <Field label="Institution">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}><option value="">Choose…</option><UnitOptions permission="question.bank.manage" allowOrganization={false} /></Select>}</Field>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="Policy">{(p) => <Select {...p} value={policy} onChange={(e) => setPolicy(e.target.value)}><option value="STANDARD">Standard</option><option value="HIGH_STAKES">High-stakes (three people)</option></Select>}</Field>
                  <Field label="Required reviews">{(p) => <Select {...p} value={String(reviews)} onChange={(e) => setReviews(Number(e.target.value))}><option>1</option><option>2</option><option>3</option></Select>}</Field>
                </div>
                {policy === "STANDARD" && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={distinct} onChange={(e) => setDistinct(e.target.checked)} />Approver must not have reviewed</label>}
                <Button variant="primary" disabled={!unit} loading={save.isPending} onClick={() => save.mutate()}>Save minimum</Button>
              </>}
            </CardBody>
          </Card>
          {manage && <Card>
            <CardHeader title="Image storage quotas" description="Applies to all banks of the institution and the units below it." />
            <CardBody className="space-y-4">
              {quota.isError && <Alert tone="danger">{errorInfo(quota.error).message}</Alert>}
              {quota.data && <Alert tone="success">Quota set: {((quota.data.usedBytes ?? 0) / 1048576).toFixed(1)} MB of {((quota.data.limitBytes ?? 0) / 1048576).toFixed(0)} MB used.</Alert>}
              <Field label="Scope">{(p) => <Select {...p} value={quotaUnit} onChange={(e) => setQuotaUnit(e.target.value)}>{!access.has("question.bank.manage") && <option value="">Choose…</option>}<UnitOptions permission="question.bank.manage" /></Select>}</Field>
              <Field label="Limit (MB)">{(p) => <Input {...p} type="number" min={5} value={quotaMb} onChange={(e) => setQuotaMb(e.target.value)} />}</Field>
              <Button variant="primary" loading={quota.isPending} onClick={() => quota.mutate()}>Set quota</Button>
            </CardBody>
          </Card>}
        </div>
      </QuestionTabs>
    </>
  );
}

export default QuestionPoliciesPage;
