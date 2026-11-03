import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type StudentDetail } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, CardHeader, Field, Input, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useNavigate, useParams } from "react-router";
import { z } from "zod";
import { useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { ancestorIds, useOrgTree } from "../../session/useOrgTree";
import { DetailError, fieldError, UnitChoices, useIdempotencyKey } from "../academics/AcademicsShared";
import { useInvalidateStudents } from "./StudentShared";

const EMAIL = z.union([z.literal(""), z.string().trim().email("Enter a valid email address").max(320)]);
const PHONE = z.union([z.literal(""), z.string().trim().regex(/^\+?[0-9][0-9 -]{5,18}[0-9]$/, "7–20 digits; spaces, - and a leading + allowed")]);
const schema = z.object({
  institutionId: z.string().min(1, "Choose the university or college"),
  studentNo: z.string().trim().max(40, "At most 40 characters"),
  admissionYear: z.string().trim().refine((v) => v === "" || /^\d{4}$/.test(v), "A year such as 2026"),
  registrationNo: z.string().trim().max(40, "At most 40 characters"),
  rollNo: z.string().trim().max(40, "At most 40 characters"),
  previousInstitutionRef: z.string().trim().max(100, "At most 100 characters"),
  firstName: z.string().trim().min(1, "Required").max(100, "At most 100 characters"),
  middleName: z.string().trim().max(100, "At most 100 characters"),
  lastName: z.string().trim().max(100, "At most 100 characters"),
  institutionalEmail: EMAIL,
  dateOfBirth: z.string().refine((v) => v === "" || /^\d{4}-\d{2}-\d{2}$/.test(v), "Choose a date"),
  gender: z.string(),
  nationality: z.string().trim().max(60, "At most 60 characters"),
  personalEmail: EMAIL,
  mobile: PHONE,
  alternatePhone: PHONE,
  currentAddress: z.string().trim().max(500, "At most 500 characters"),
  permanentAddress: z.string().trim().max(500, "At most 500 characters"),
});
type Values = z.infer<typeof schema>;

/** Create or edit a student profile. */
export function StudentEditorPage() {
  const { id } = useParams();
  const api = useApi();
  const existing = useQuery({ queryKey: ["students", "detail", id], enabled: !!id,
    queryFn: async () => unwrap(await api.GET("/api/v1/students/{id}", { params: { path: { id: id! } } })) });
  if (id && existing.isPending) return <Skeleton className="h-96 w-full" />;
  if (id && existing.isError) return <DetailError error={existing.error} what="Student" onRetry={() => void existing.refetch()} />;
  return <StudentForm key={existing.data?.version ?? "new"} detail={existing.data} />;
}

function StudentForm({ detail }: { detail: StudentDetail | undefined }) {
  const api = useApi();
  const navigate = useNavigate();
  const toast = useToast();
  const labels = useOrgLabels();
  const { access } = useSession();
  const tree = useOrgTree();
  const invalidate = useInvalidateStudents();
  const key = useIdempotencyKey();
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!detail;
  const s = detail?.student;
  const p = detail?.personal;
  const rules = useQuery({ queryKey: ["students", "numbering-rules"], enabled: !editing,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-settings/numbering-rules")) });
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: {
    institutionId: s?.institutionId ?? "", studentNo: s?.studentNo ?? "", admissionYear: String(new Date().getFullYear()), registrationNo: s?.registrationNo ?? "",
    rollNo: s?.rollNo ?? "", previousInstitutionRef: detail?.previousInstitutionRef ?? "", firstName: s?.firstName ?? "", middleName: s?.middleName ?? "",
    lastName: s?.lastName ?? "", institutionalEmail: detail?.institutionalEmail ?? "", dateOfBirth: p?.dateOfBirth ?? "", gender: p?.gender ?? "",
    nationality: p?.nationality ?? "", personalEmail: p?.personalEmail ?? "", mobile: p?.mobile ?? "", alternatePhone: p?.alternatePhone ?? "",
    currentAddress: p?.currentAddress ?? "", permanentAddress: p?.permanentAddress ?? "" } });
  const institutionId = useWatch({ control: form.control, name: "institutionId" });
  const rule = (rules.data ?? []).find((r) => r.orgUnitId === institutionId);
  const generated = !editing && rule?.mode === "GENERATED";
  const personalAllowed = editing ? !!detail.personalVisible
    : !!institutionId && access.hasInOrgUnit("student.personal.read", institutionId, ancestorIds(tree.data, institutionId));

  const save = useMutation({
    mutationFn: async (v: Values) => {
      const opt = (x: string) => (x ? x : undefined);
      const personal = personalAllowed ? { personal: { dateOfBirth: opt(v.dateOfBirth), gender: (opt(v.gender) as "FEMALE" | undefined), nationality: opt(v.nationality),
        personalEmail: opt(v.personalEmail), mobile: opt(v.mobile), alternatePhone: opt(v.alternatePhone), currentAddress: opt(v.currentAddress),
        permanentAddress: opt(v.permanentAddress) } } : {};
      const body = { firstName: v.firstName, middleName: opt(v.middleName), lastName: opt(v.lastName), registrationNo: opt(v.registrationNo), rollNo: opt(v.rollNo),
        previousInstitutionRef: opt(v.previousInstitutionRef), institutionalEmail: opt(v.institutionalEmail), ...personal };
      if (editing) return unwrap(await api.PATCH("/api/v1/students/{id}", { params: { path: { id: s!.id! } }, body: { ...body, version: detail.version! } }));
      return unwrap(await api.POST("/api/v1/students", { headers: { "Idempotency-Key": key.get() }, body: { ...body, institutionId: v.institutionId,
        ...(generated ? { admissionYear: Number(v.admissionYear) } : { studentNo: v.studentNo }) } }));
    },
    onSuccess: (d) => { invalidate(); toast.show("success", editing ? "Student saved" : "Student created", `${d.student?.fullName} · ${d.student?.studentNo}`);
      void navigate(`/students/${d.student?.id}`); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { invalid_studentNo: "studentNo", student_no_in_use: "studentNo", student_no_generated: "studentNo", invalid_registrationNo: "registrationNo",
        registration_no_in_use: "registrationNo", invalid_rollNo: "rollNo", invalid_firstName: "firstName", invalid_institutionalEmail: "institutionalEmail",
        invalid_personalEmail: "personalEmail", invalid_mobile: "mobile", invalid_alternatePhone: "alternatePhone", invalid_dateOfBirth: "dateOfBirth",
        invalid_institutionId: "institutionId" }, (f, m) => form.setError(f as keyof Values, { message: m })));
    },
  });
  const err = form.formState.errors;
  const back = editing ? `/students/${s!.id}` : "/students/list";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Student list", to: "/students/list" },
        ...(editing ? [{ label: s!.fullName ?? "", to: back }] : []), { label: editing ? "Edit" : "New student" }]} /></div>
      <PageHeader title={editing ? `Edit ${s!.fullName}` : "New student"}
        description={editing ? "Changes are recorded in the student's history (without personal values)." : "Creates a prospective student. Record the admission next, then enrol the student in a batch."} />
      <form noValidate onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })} className="space-y-6">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Card>
          <CardHeader title="Identification" />
          <CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
            <Field label={`${labels.singular("UNIVERSITY")} or ${labels.singular("COLLEGE").toLowerCase()}`} error={err.institutionId?.message}
              hint={editing ? "The home institution cannot change." : "Its numbering rule decides the student ID."}>
              {(f) => <Select {...f} disabled={editing} {...form.register("institutionId")}>
                {editing ? <option value={s!.institutionId}>{s!.institutionName}</option> : <><option value="">Choose…</option>
                  <UnitChoices permission="student.manage" types={["UNIVERSITY", "COLLEGE"]} /></>}</Select>}
            </Field>
            {generated ? (
              <Field label="Admission year" error={err.admissionYear?.message} hint={`The student ID is generated (${rule?.template}), e.g. ${rule?.example}.`}>
                {(f) => <Input {...f} inputMode="numeric" {...form.register("admissionYear")} />}
              </Field>
            ) : (
              <Field label="Student ID" error={err.studentNo?.message}
                hint={editing ? "Cannot change once assigned." : rule?.manualPattern ? `Format: ${rule.manualPattern} (A letter, 9 digit, X letter or digit, * several)` : "Letters, digits and / - . _ (3–40)."}>
                {(f) => <Input {...f} disabled={editing} autoComplete="off" {...form.register("studentNo")} />}
              </Field>
            )}
            <Field label="Registration number (optional)" error={err.registrationNo?.message} hint={rule?.registrationPattern ? `Format: ${rule.registrationPattern}` : undefined}>
              {(f) => <Input {...f} autoComplete="off" {...form.register("registrationNo")} />}
            </Field>
            <Field label="Roll number (optional)" error={err.rollNo?.message}>{(f) => <Input {...f} autoComplete="off" {...form.register("rollNo")} />}</Field>
            <Field label="Previous institution ID (optional)" error={err.previousInstitutionRef?.message}>{(f) => <Input {...f} {...form.register("previousInstitutionRef")} />}</Field>
            <Field label="Institutional email (optional)" error={err.institutionalEmail?.message} hint="Used for the sign-in account if one is created.">
              {(f) => <Input {...f} type="email" autoComplete="off" {...form.register("institutionalEmail")} />}
            </Field>
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Name" />
          <CardBody className="grid grid-cols-1 gap-5 md:grid-cols-3">
            <Field label="First name" error={err.firstName?.message}>{(f) => <Input {...f} autoComplete="off" {...form.register("firstName")} />}</Field>
            <Field label="Middle name (optional)" error={err.middleName?.message}>{(f) => <Input {...f} autoComplete="off" {...form.register("middleName")} />}</Field>
            <Field label="Last name (optional)" error={err.lastName?.message}>{(f) => <Input {...f} autoComplete="off" {...form.register("lastName")} />}</Field>
          </CardBody>
        </Card>
        {personalAllowed ? (
          <Card>
            <CardHeader title="Personal and contact details" description="Stored encrypted; visible only to people allowed to see personal details." />
            <CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
              <Field label="Date of birth (optional)" error={err.dateOfBirth?.message}>{(f) => <Input {...f} type="date" {...form.register("dateOfBirth")} />}</Field>
              <Field label="Gender (where the institution requires it)" error={err.gender?.message}>
                {(f) => <Select {...f} {...form.register("gender")}><option value="">Not recorded</option><option value="FEMALE">Female</option><option value="MALE">Male</option>
                  <option value="OTHER">Other</option><option value="UNDISCLOSED">Prefer not to say</option></Select>}
              </Field>
              <Field label="Nationality (optional)" error={err.nationality?.message}>{(f) => <Input {...f} {...form.register("nationality")} />}</Field>
              <Field label="Personal email (optional)" error={err.personalEmail?.message}>{(f) => <Input {...f} type="email" autoComplete="off" {...form.register("personalEmail")} />}</Field>
              <Field label="Mobile (optional)" error={err.mobile?.message}>{(f) => <Input {...f} type="tel" autoComplete="off" {...form.register("mobile")} />}</Field>
              <Field label="Alternate number (optional)" error={err.alternatePhone?.message}>{(f) => <Input {...f} type="tel" autoComplete="off" {...form.register("alternatePhone")} />}</Field>
              <Field label="Current address (optional)" error={err.currentAddress?.message}>{(f) => <TextArea {...f} rows={3} {...form.register("currentAddress")} />}</Field>
              <Field label="Permanent address (optional)" error={err.permanentAddress?.message}>{(f) => <TextArea {...f} rows={3} {...form.register("permanentAddress")} />}</Field>
              <div className="md:col-span-2">
                <Button size="sm" variant="ghost" onClick={() => form.setValue("permanentAddress", form.getValues("currentAddress"), { shouldDirty: true })}>Same as current address</Button>
              </div>
            </CardBody>
          </Card>
        ) : institutionId || editing ? (
          <Alert tone="info">Personal and contact details are only visible to people with permission to see them; they are left unchanged.</Alert>
        ) : null}
        <div className="flex flex-wrap justify-end gap-3">
          <ButtonLink to={back}>Cancel</ButtonLink>
          <Button type="submit" loading={save.isPending}>{editing ? "Save changes" : "Create student"}</Button>
        </div>
      </form>
    </>
  );
}

export default StudentEditorPage;
