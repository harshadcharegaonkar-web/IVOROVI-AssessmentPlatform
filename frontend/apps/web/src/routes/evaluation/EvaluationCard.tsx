import { unwrap, type ModerationItem } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, Field, Input, Modal, Select, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";

/**
 * Manual evaluation of a scheduled examination (evaluation.manage, contract C-2): single or double marking, the moderation
 * tolerance and the evaluator pool; allocation of anonymous scripts; progress; and moderation of disagreeing marks.
 */
export function EvaluationCard({ scheduleId }: { scheduleId: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const path = { params: { path: { scheduleId } } };
  const setup = useQuery({ queryKey: ["evaluation-setup", scheduleId], retry: false, queryFn: () => loadSetup(api, scheduleId) });
  const progress = useQuery({ queryKey: ["evaluation-progress", scheduleId], retry: false, refetchInterval: 15_000, enabled: !!setup.data?.configured,
    queryFn: async () => unwrap(await api.GET("/api/v1/evaluation/schedules/{scheduleId}/progress", path)) });
  const moderation = useQuery({ queryKey: ["evaluation-moderation", scheduleId], retry: false, refetchInterval: 15_000, enabled: !!setup.data?.configured,
    queryFn: async () => unwrap(await api.GET("/api/v1/evaluation/schedules/{scheduleId}/moderation", path)) });
  const [moderating, setModerating] = useState<ModerationItem | null>(null);
  const refresh = () => { for (const k of ["evaluation-setup", "evaluation-progress", "evaluation-moderation", "exam-results"]) void qc.invalidateQueries({ queryKey: [k, scheduleId] }); };
  const allocate = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/evaluation/schedules/{scheduleId}/allocate", path)), onSuccess: refresh });
  if (setup.isError) return null;                                                     // no submitted attempts yet, or not permitted
  const s = setup.data;
  if (!s) return null;
  const p = progress.data;
  return (
    <Card>
      <CardHeader title="Evaluation" description="Written answers are marked by evaluators on anonymous scripts: they never see who wrote an answer." />
      <CardBody className="space-y-4">
        <SetupForm key={`${s.mode}|${s.tolerancePercent}|${(s.evaluators ?? []).map((e) => e.userId).join(",")}`} scheduleId={scheduleId} setup={s} onSaved={refresh} />
        {allocate.isError && <Alert tone="danger">{errorInfo(allocate.error).message}</Alert>}
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" loading={allocate.isPending} disabled={!s.configured} onClick={() => allocate.mutate()}>Allocate scripts</Button>
        </div>
        {p && (
          <div className="space-y-2 text-sm" data-testid="evaluation-progress">
            <p>{p.scripts} script(s){p.awaitingScripts ? ` · ${p.awaitingScripts} waiting for allocation` : ""} · answers: {p.itemsOpen} open · {p.itemsInModeration} in moderation · {p.itemsFinal} final</p>
            <ul className="space-y-1">{(p.evaluators ?? []).map((l) => <li key={l.evaluatorId}>{l.displayName}: {l.assigned} to mark · {l.submitted} submitted</li>)}</ul>
          </div>)}
        {(moderation.data ?? []).length > 0 && (
          <div className="space-y-2">
            <h3 className="text-sm font-semibold">Moderation</h3>
            <ul className="space-y-2">{moderation.data!.map((m) => (
              <li key={m.itemId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border p-3 text-sm">
                <span><span className="font-medium">{m.scriptCode}</span> · question {m.itemNo} · {(m.marksGiven ?? []).map((g) => `${g.label}: ${g.total}`).join(" · ")} of {m.marks}
                  <span className="text-muted"> (tolerance {m.tolerance})</span></span>
                {m.mayModerate ? <Button variant="secondary" onClick={() => setModerating(m)}>Moderate</Button> : <Badge tone="neutral">You evaluated this answer</Badge>}
              </li>))}</ul>
          </div>)}
      </CardBody>
      {moderating && <ModerateDialog item={moderating} onClose={() => setModerating(null)} onDone={() => { setModerating(null); refresh(); }} />}
    </Card>
  );
}

type Setup = NonNullable<Awaited<ReturnType<typeof loadSetup>>>;
async function loadSetup(api: ReturnType<typeof useApi>, scheduleId: string) {
  return unwrap(await api.GET("/api/v1/evaluation/schedules/{scheduleId}/setup", { params: { path: { scheduleId } } }));
}

function SetupForm({ scheduleId, setup: s, onSaved }: { scheduleId: string; setup: Setup; onSaved: () => void }) {
  const api = useApi();
  const [mode, setMode] = useState(s.mode ?? "SINGLE");
  const [tolerance, setTolerance] = useState(String(s.tolerancePercent ?? 10));
  const [pool, setPool] = useState<string[]>((s.evaluators ?? []).map((e) => e.userId!));
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/evaluation/schedules/{scheduleId}/setup", { params: { path: { scheduleId } },
      body: { mode, tolerancePercent: Number(tolerance), evaluatorIds: pool } })),
    onSuccess: onSaved,
  });
  return (
    <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Marking">{(f) => <Select {...f} value={mode} disabled={s.locked} onChange={(e) => setMode(e.target.value)}>
            <option value="SINGLE">Single marking</option><option value="DOUBLE">Double marking (two evaluators)</option></Select>}</Field>
          {mode === "DOUBLE" && <Field label="Moderation tolerance (%)" hint="Two marks further apart than this go to moderation">{(f) =>
            <Input {...f} type="number" min={0} max={100} value={tolerance} disabled={s.locked} onChange={(e) => setTolerance(e.target.value)} />}</Field>}
        </div>
        <fieldset>
          <legend className="text-sm font-medium">Evaluators</legend>
          {(s.eligible ?? []).length === 0 && <p className="text-sm text-muted">Nobody in this institution may evaluate yet (permission “Evaluate answers”).</p>}
          <div className="mt-2 grid gap-1 sm:grid-cols-2">{(s.eligible ?? []).map((e) => (
            <label key={e.userId} className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={pool.includes(e.userId!)} onChange={(ev) => setPool(ev.target.checked ? [...pool, e.userId!] : pool.filter((x) => x !== e.userId))} />
              {e.displayName}</label>))}</div>
        </fieldset>
        {s.locked && <p className="text-sm text-muted">Marks have been submitted: the marking mode and tolerance are fixed; evaluators can still be added.</p>}
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Button variant="secondary" loading={save.isPending} disabled={pool.length === 0} onClick={() => save.mutate()}>Save evaluation setup</Button>
    </div>
  );
}

function ModerateDialog({ item, onClose, onDone }: { item: ModerationItem; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [awarded, setAwarded] = useState("");
  const [reason, setReason] = useState("");
  const decide = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/evaluation/moderation/{itemId}", { params: { path: { itemId: item.itemId! } }, body: { awarded: Number(awarded), reason } })),
    onSuccess: onDone,
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Moderate ${item.scriptCode}, question ${item.itemNo}`}
      description="Set the final mark. Your reason is kept permanently with the result."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={decide.isPending} disabled={!awarded || !reason.trim()} onClick={() => decide.mutate()}>Set final mark</Button></>}>
      <div className="space-y-3 text-sm">
        {(item.marksGiven ?? []).map((g) => (
          <div key={g.label} className="rounded-md border border-border p-2">
            <p className="font-medium">{g.label}: {g.total} of {item.marks}</p>
            <ul>{(g.criterionMarks ?? []).map((c) => <li key={c.criterionId}>{c.title}: {c.awarded} / {c.max}</li>)}</ul>
            {g.comment && <p className="text-muted">“{g.comment}”</p>}
          </div>))}
        {decide.isError && <Alert tone="danger">{errorInfo(decide.error).message}</Alert>}
        <Field label={`Final mark (0–${item.marks})`}>{(f) => <Input {...f} inputMode="decimal" value={awarded} onChange={(e) => setAwarded(e.target.value)} />}</Field>
        <Field label="Reason">{(f) => <TextArea {...f} rows={3} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

