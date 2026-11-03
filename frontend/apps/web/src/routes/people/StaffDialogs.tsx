import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type StaffDetail } from "@ivorovi/api-client";
import { Alert, Button, Field, Input, Modal, Select, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { invalidatePeople, UnitOptions } from "./PeopleDialogs";
import { EMPLOYMENT_STATUS, EMPLOYMENT_TYPES, STAFF_CATEGORIES, type EmploymentStatus, type EmploymentType, type StaffCategory } from "./peopleModel";

const schema = z.object({
  staffNo: z.string().trim().min(1, "Staff ID is required").max(50).regex(/^[A-Za-z0-9][A-Za-z0-9/_.-]*$/, "Letters, digits and / _ . - only"),
  givenName: z.string().trim().min(1, "First name is required").max(100),
  familyName: z.string().trim().max(100),
  email: z.string().trim().max(320).refine((v) => !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), "Enter a valid email address"),
  phone: z.string().trim().max(30).refine((v) => !v || /^\+?[0-9 ()-]{6,30}$/.test(v), "Enter a valid phone number"),
  category: z.string().min(1, "Choose a category"),
  designation: z.string().trim().max(100),
  employmentStatus: z.string(),
  employmentType: z.string(),
  joinedOn: z.string(),
  orgUnitId: z.string(),
});
type Values = z.infer<typeof schema>;

export function StaffFormDialog({ open, onOpenChange, existing }: { open: boolean; onOpenChange: (o: boolean) => void; existing?: StaffDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const s = existing?.staff;
  const editing = !!s;
  const key = useRef(crypto.randomUUID());
  const [problem, setProblem] = useState<string | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      staffNo: s?.staffNo ?? "", givenName: s?.givenName ?? "", familyName: s?.familyName ?? "", email: s?.email ?? "", phone: s?.phone ?? "",
      category: s?.category ?? "", designation: s?.designation ?? "", employmentStatus: s?.employmentStatus ?? "ACTIVE",
      employmentType: s?.employmentType ?? "", joinedOn: s?.joinedOn ?? "", orgUnitId: "",
    },
  });
  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = {
        staffNo: v.staffNo, givenName: v.givenName, familyName: v.familyName, email: v.email, phone: v.phone,
        category: v.category as StaffCategory, designation: v.designation, employmentStatus: v.employmentStatus as EmploymentStatus,
        ...(v.employmentType ? { employmentType: v.employmentType as EmploymentType } : {}), ...(v.joinedOn ? { joinedOn: v.joinedOn } : {}),
      };
      if (editing) return unwrap(await api.PUT("/api/v1/staff/{id}", { params: { path: { id: s!.id! } }, body: { ...body, version: s!.version! } }));
      return unwrap(await api.POST("/api/v1/staff", { headers: { "Idempotency-Key": key.current }, body: { ...body, ...(v.orgUnitId ? { orgUnitId: v.orgUnitId } : {}) } }));
    },
    onSuccess: (d) => {
      invalidatePeople(qc);
      toast.show("success", editing ? "Changes saved" : "Staff profile created", d.staff?.displayName);
      onOpenChange(false);
      if (!editing && d.staff?.id) void navigate(`/people/staff/${d.staff.id}`);
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        key.current = crypto.randomUUID();
        if (e.code === "staff_no_in_use" || e.code === "invalid_staff_no") { form.setError("staffNo", { message: e.message }); return; }
        if (e.code === "invalid_email") { form.setError("email", { message: e.message }); return; }
        if (e.code === "invalid_org_unit") { form.setError("orgUnitId", { message: e.message }); return; }
      }
      setProblem(errorInfo(e).message);
    },
  });
  const err = form.formState.errors;
  return (
    <Modal open={open} onOpenChange={onOpenChange} title={editing ? "Edit staff profile" : "New staff profile"} size="lg"
      description={editing ? s!.displayName : "A staff profile does not need a login. Link it to a user account when the person needs access."}
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={save.isPending}>Cancel</Button>
        <Button type="submit" form="staff-form" loading={save.isPending}>{editing ? "Save changes" : "Create profile"}</Button>
      </>}>
      <form id="staff-form" noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2" onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })}>
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="Staff ID" error={err.staffNo?.message} hint="Employee number; unique in your organization">{(p) => <Input {...p} autoComplete="off" {...form.register("staffNo")} />}</Field>
        <Field label="Category" error={err.category?.message}>
          {(p) => <Select {...p} {...form.register("category")}><option value="">Choose…</option>
            {(Object.keys(STAFF_CATEGORIES) as StaffCategory[]).map((c) => <option key={c} value={c}>{STAFF_CATEGORIES[c]}</option>)}</Select>}
        </Field>
        <Field label="First name" error={err.givenName?.message}>{(p) => <Input {...p} {...form.register("givenName")} />}</Field>
        <Field label="Last name" error={err.familyName?.message}>{(p) => <Input {...p} {...form.register("familyName")} />}</Field>
        <Field label="Email" error={err.email?.message}>{(p) => <Input {...p} type="email" {...form.register("email")} />}</Field>
        <Field label="Phone" error={err.phone?.message}>{(p) => <Input {...p} type="tel" {...form.register("phone")} />}</Field>
        <Field label="Designation" error={err.designation?.message} hint="e.g. Associate Professor">{(p) => <Input {...p} {...form.register("designation")} />}</Field>
        <Field label="Joining date">{(p) => <Input {...p} type="date" {...form.register("joinedOn")} />}</Field>
        <Field label="Employment status">
          {(p) => <Select {...p} {...form.register("employmentStatus")}>{(Object.keys(EMPLOYMENT_STATUS) as EmploymentStatus[]).map((e) => <option key={e} value={e}>{EMPLOYMENT_STATUS[e].label}</option>)}</Select>}
        </Field>
        <Field label="Employment type">
          {(p) => <Select {...p} {...form.register("employmentType")}><option value="">Not specified</option>
            {(Object.keys(EMPLOYMENT_TYPES) as EmploymentType[]).map((e) => <option key={e} value={e}>{EMPLOYMENT_TYPES[e]}</option>)}</Select>}
        </Field>
        {!editing && (
          <Field label="Department, campus or college" className="sm:col-span-2" error={err.orgUnitId?.message} hint="Primary assignment; more can be added later.">
            {(p) => <Select {...p} {...form.register("orgUnitId")}><UnitOptions permission="staff.manage" /></Select>}
          </Field>
        )}
      </form>
    </Modal>
  );
}

/** Link a staff profile to an existing user account (search by name or email). */
export function LinkUserDialog({ open, onOpenChange, staffId, suggestedEmail }: { open: boolean; onOpenChange: (o: boolean) => void; staffId: string; suggestedEmail?: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [q, setQ] = useState(suggestedEmail ?? "");
  const [userId, setUserId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const users = useQuery({ queryKey: ["users", "link-search", q], enabled: q.trim().length >= 2,
    queryFn: async () => unwrap(await api.GET("/api/v1/users", { params: { query: { q: q.trim(), size: 10 } } })) });
  const link = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/staff/{id}/link", { params: { path: { id: staffId } }, body: { userId } })),
    onSuccess: (d) => { invalidatePeople(qc); toast.show("success", "Linked to user account", d.staff?.userEmail); onOpenChange(false); },
    onError: (e) => setError(errorInfo(e).message),
  });
  const candidates = (users.data?.items ?? []).filter((u) => !u.staffProfileId);
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Link to user account"
      description="The person keeps one staff profile and one login. Create and invite the user first if they do not have an account."
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={link.isPending}>Cancel</Button>
        <Button disabled={!userId} loading={link.isPending} onClick={() => { setError(null); link.mutate(); }}>Link account</Button>
      </>}>
      <div className="space-y-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Find user" hint="At least 2 characters of a name or email">{(p) => <Input {...p} type="search" value={q} onChange={(e) => { setQ(e.target.value); setUserId(""); }} />}</Field>
        <fieldset className="space-y-2">
          <legend className="sr-only">Matching users</legend>
          {q.trim().length >= 2 && !users.isPending && candidates.length === 0 && <p className="text-sm text-muted">No unlinked users match.</p>}
          {candidates.map((u) => (
            <label key={u.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-border p-3 text-sm has-[:checked]:border-primary has-[:checked]:bg-primary-soft">
              <input type="radio" name="link-user" value={u.id} checked={userId === u.id} onChange={() => setUserId(u.id!)} className="accent-[var(--color-primary)]" />
              <span className="min-w-0"><span className="block font-medium text-fg">{u.displayName}</span><span className="block truncate text-muted">{u.email}</span></span>
            </label>
          ))}
        </fieldset>
      </div>
    </Modal>
  );
}
