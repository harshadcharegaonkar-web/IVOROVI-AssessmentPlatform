import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type AcademicTerm, type AcademicYear } from "@ivorovi/api-client";
import { Alert, Button, Field, Input, Modal, Select, useToast } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { fieldError, StatusDialog, UnitChoices, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { calendarTransitions, CODE_PATTERN, formatDate, statusLabel } from "./academicsModel";

const DATE = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Choose a date");
const OPTIONAL_DATE = z.union([z.literal(""), DATE]);

const yearSchema = z.object({
  code: z.string().trim().min(1, "Required").max(30, "At most 30 characters").regex(CODE_PATTERN, "Letters, digits and . _ / -"),
  name: z.string().trim().min(1, "Required").max(100, "At most 100 characters"),
  ownerOrgUnitId: z.string(),
  startDate: DATE,
  endDate: DATE,
}).refine((v) => v.endDate > v.startDate, { path: ["endDate"], message: "Must be after the start date" });
type YearValues = z.infer<typeof yearSchema>;

/** Create or edit an academic year (organization-wide, or for one university or college). */
export function YearDialog({ year, onClose, onSaved }: { year?: AcademicYear | undefined; onClose: () => void; onSaved?: (id: string) => void }) {
  const api = useApi();
  const toast = useToast();
  const { access } = useSession();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!year;
  const form = useForm<YearValues>({ resolver: zodResolver(yearSchema), defaultValues: {
    code: year?.code ?? "", name: year?.name ?? "", ownerOrgUnitId: year?.ownerOrgUnitId ?? (access.has("academic_calendar.manage") ? "" : "__choose"),
    startDate: year?.startDate ?? "", endDate: year?.endDate ?? "" } });
  const save = useMutation({
    mutationFn: async (v: YearValues) => {
      const body = { code: v.code, name: v.name, startDate: v.startDate, endDate: v.endDate };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/academic-years/{id}", { params: { path: { id: year.id! } }, body: { ...body, version: year.version! } }));
      return unwrap(await api.POST("/api/v1/academics/academic-years", { headers: { "Idempotency-Key": key.get() },
        body: { ...body, ...(v.ownerOrgUnitId && v.ownerOrgUnitId !== "__choose" ? { ownerOrgUnitId: v.ownerOrgUnitId } : {}) } }));
    },
    onSuccess: (d) => { invalidate(); toast.show("success", editing ? "Academic year saved" : "Academic year created", d.year?.name); onSaved?.(d.year!.id!); onClose(); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { code_in_use: "code", invalid_code: "code", year_overlap: "startDate", invalid_dates: "endDate", terms_outside_year: "startDate", invalid_owner: "ownerOrgUnitId" },
        (f, m) => form.setError(f as keyof YearValues, { message: m })));
    },
  });
  const err = form.formState.errors;
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={editing ? `Edit ${year.name}` : "New academic year"}
      description="Years of the same owner cannot overlap unless System Settings allows it."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={form.handleSubmit((v) => {
        if (v.ownerOrgUnitId === "__choose") { form.setError("ownerOrgUnitId", { message: "Choose an owner" }); return; }
        setProblem(null); save.mutate(v);
      })}>{editing ? "Save" : "Create"}</Button></>}>
      <form noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2" onSubmit={(e) => e.preventDefault()}>
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="Code" error={err.code?.message} hint="e.g. AY2026-27">{(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}</Field>
        <Field label="Name" error={err.name?.message} hint="e.g. 2026–27">{(p) => <Input {...p} {...form.register("name")} />}</Field>
        <div className="sm:col-span-2">
          <Field label="Applies to" error={err.ownerOrgUnitId?.message} hint={editing ? "Cannot change." : "Organization-wide years apply to every institution."}>
            {(p) => <Select {...p} disabled={editing} {...form.register("ownerOrgUnitId")}>
              {editing ? <option value={year.ownerOrgUnitId ?? ""}>{year.ownerName ?? "Whole organization"}</option> : <>
                {!access.has("academic_calendar.manage") && <option value="__choose">Choose…</option>}
                <UnitChoices permission="academic_calendar.manage" types={["UNIVERSITY", "COLLEGE"]} organizationLabel="Whole organization" /></>}
            </Select>}
          </Field>
        </div>
        <Field label="Starts on" error={err.startDate?.message}>{(p) => <Input {...p} type="date" {...form.register("startDate")} />}</Field>
        <Field label="Ends on" error={err.endDate?.message}>{(p) => <Input {...p} type="date" {...form.register("endDate")} />}</Field>
      </form>
    </Modal>
  );
}

const termSchema = z.object({
  sequence: z.string().refine((v) => Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 12, "1 to 12"),
  name: z.string().trim().min(1, "Required").max(100, "At most 100 characters"),
  startDate: DATE, endDate: DATE,
  registrationStart: OPTIONAL_DATE, registrationEnd: OPTIONAL_DATE, examStart: OPTIONAL_DATE, examEnd: OPTIONAL_DATE,
}).superRefine((v, ctx) => {
  if (v.endDate <= v.startDate) ctx.addIssue({ code: "custom", path: ["endDate"], message: "Must be after the start date" });
  if (!!v.registrationStart !== !!v.registrationEnd) ctx.addIssue({ code: "custom", path: ["registrationEnd"], message: "Give both dates or neither" });
  if (v.registrationStart && v.registrationEnd && v.registrationEnd < v.registrationStart) ctx.addIssue({ code: "custom", path: ["registrationEnd"], message: "Must be on or after it opens" });
  if (v.registrationEnd && v.registrationEnd > v.endDate) ctx.addIssue({ code: "custom", path: ["registrationEnd"], message: "Must close by the end of the term" });
  if (!!v.examStart !== !!v.examEnd) ctx.addIssue({ code: "custom", path: ["examEnd"], message: "Give both dates or neither" });
  if (v.examStart && (v.examStart < v.startDate || v.examStart > v.endDate)) ctx.addIssue({ code: "custom", path: ["examStart"], message: "Must fall within the term" });
  if (v.examEnd && (v.examEnd > v.endDate || (v.examStart && v.examEnd < v.examStart))) ctx.addIssue({ code: "custom", path: ["examEnd"], message: "Must fall within the term, after the start" });
});
type TermValues = z.infer<typeof termSchema>;

/** Create or edit a term of a year. Dates are checked here for convenience; the server enforces every rule. */
export function TermDialog({ year, term, nextSequence, onClose }: { year: AcademicYear; term?: AcademicTerm | undefined; nextSequence?: number; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const labels = useOrgLabels();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!term;
  const noun = labels.singular("TERM");
  const seq = term?.sequence ?? nextSequence ?? 1;
  const form = useForm<TermValues>({ resolver: zodResolver(termSchema), defaultValues: {
    sequence: String(seq), name: term?.name ?? `${noun} ${seq}`, startDate: term?.startDate ?? "", endDate: term?.endDate ?? "",
    registrationStart: term?.registrationStart ?? "", registrationEnd: term?.registrationEnd ?? "", examStart: term?.examStart ?? "", examEnd: term?.examEnd ?? "" } });
  const save = useMutation({
    mutationFn: async (v: TermValues) => {
      const body = { sequence: Number(v.sequence), name: v.name, startDate: v.startDate, endDate: v.endDate,
        ...(v.registrationStart ? { registrationStart: v.registrationStart, registrationEnd: v.registrationEnd } : {}),
        ...(v.examStart ? { examStart: v.examStart, examEnd: v.examEnd } : {}) };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/academic-terms/{id}", { params: { path: { id: term.id! } }, body: { ...body, version: term.version! } }));
      return unwrap(await api.POST("/api/v1/academics/academic-years/{id}/terms", { params: { path: { id: year.id! } }, headers: { "Idempotency-Key": key.get() }, body }));
    },
    onSuccess: (t) => { invalidate(); toast.show("success", editing ? `${noun} saved` : `${noun} added`, t.name); onClose(); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { sequence_in_use: "sequence", invalid_sequence: "sequence", term_outside_year: "startDate", term_overlap: "startDate",
        invalid_dates: "endDate", invalid_registration_window: "registrationEnd", invalid_exam_window: "examStart" },
        (f, m) => form.setError(f as keyof TermValues, { message: m })));
    },
  });
  const err = form.formState.errors;
  return (
    <Modal open size="lg" onOpenChange={(o) => !o && onClose()} title={editing ? `Edit ${term.name}` : `Add a ${noun.toLowerCase()} to ${year.name}`}
      description={`Within ${formatDate(year.startDate)} – ${formatDate(year.endDate)}; ${labels.plural("TERM").toLowerCase()} of a year never overlap.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={save.isPending} onClick={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })}>{editing ? "Save" : "Add"}</Button></>}>
      <form noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2" onSubmit={(e) => e.preventDefault()}>
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="Number" error={err.sequence?.message} hint="Order within the year.">{(p) => <Input {...p} inputMode="numeric" {...form.register("sequence")} />}</Field>
        <Field label="Name" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
        <Field label="Starts on" error={err.startDate?.message}>{(p) => <Input {...p} type="date" min={year.startDate} max={year.endDate} {...form.register("startDate")} />}</Field>
        <Field label="Ends on" error={err.endDate?.message}>{(p) => <Input {...p} type="date" min={year.startDate} max={year.endDate} {...form.register("endDate")} />}</Field>
        <Field label="Registration opens (optional)" error={err.registrationStart?.message}>{(p) => <Input {...p} type="date" {...form.register("registrationStart")} />}</Field>
        <Field label="Registration closes" error={err.registrationEnd?.message}>{(p) => <Input {...p} type="date" {...form.register("registrationEnd")} />}</Field>
        <Field label="Examinations start (optional)" error={err.examStart?.message}>{(p) => <Input {...p} type="date" {...form.register("examStart")} />}</Field>
        <Field label="Examinations end" error={err.examEnd?.message}>{(p) => <Input {...p} type="date" {...form.register("examEnd")} />}</Field>
      </form>
    </Modal>
  );
}

/** Status change of a year or term (planned → active → completed; corrections need a reason). */
export function CalendarStatusDialog({ kind, item, to, onClose }: { kind: "year" | "term"; item: AcademicYear | AcademicTerm; to: "PLANNED" | "ACTIVE" | "COMPLETED"; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [problem, setProblem] = useState<string | null>(null);
  const correction = calendarTransitions(item.status).find((t) => t.to === to)?.correction ?? false;
  const run = useMutation({
    mutationFn: async (reason: string) => {
      const body = { status: to, version: item.version!, ...(reason ? { reason } : {}) };
      if (kind === "year") return unwrap(await api.POST("/api/v1/academics/academic-years/{id}/status", { params: { path: { id: item.id! } }, body })).year?.name;
      return unwrap(await api.POST("/api/v1/academics/academic-terms/{id}/status", { params: { path: { id: item.id! } }, body })).name;
    },
    onSuccess: (name) => { invalidate(); toast.show("success", `Now ${statusLabel(to).toLowerCase()}`, name); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const verb = to === "ACTIVE" ? (item.status === "COMPLETED" ? "Re-open" : "Activate") : to === "COMPLETED" ? "Complete" : "Return to planned";
  return (
    <StatusDialog open onOpenChange={(o) => !o && onClose()} busy={run.isPending} error={problem} reasonRequired={correction}
      title={`${verb} ${item.name}?`} confirmLabel={verb}
      description={correction ? "This corrects an earlier status change and is recorded with your reason." :
        to === "COMPLETED" ? "Completed calendars are read-only until re-opened." : "It becomes the current calendar entry."}
      onConfirm={(r) => run.mutate(r)} />
  );
}
