import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type OrgUnitDetail, type OrgUnitType } from "@ivorovi/api-client";
import { Alert, Button, ConfirmDialog, Field, Input, Modal, Select, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useOrgTree } from "../../session/useOrgTree";
import { isInstitution, MAY_BE_TOP_LEVEL, parentOptions } from "./orgModel";

const schema = z.object({
  parentId: z.string(),
  code: z.string().trim().min(1, "Code is required").max(63)
    .regex(/^[A-Za-z0-9][A-Za-z0-9_-]*$/, "Letters, digits, '-' or '_' (starting with a letter or digit)"),
  name: z.string().trim().min(1, "Name is required").max(200),
  shortName: z.string().trim().max(50),
  city: z.string().trim().max(300),
  state: z.string().trim().max(300),
  website: z.string().trim().max(300).refine((v) => !v || /^https?:\/\/\S+$/.test(v), "Use a full URL (https://…)"),
  email: z.string().trim().max(300).refine((v) => !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), "Enter a valid email address"),
  accentColor: z.string().trim().refine((v) => !v || /^#[0-9a-fA-F]{6}$/.test(v), "Use #RRGGBB"),
});
type FormValues = z.infer<typeof schema>;

function invalidate(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ["org-units"] });
  void qc.invalidateQueries({ queryKey: ["org-unit"] });
}

/** Create (type + optional preset parent) or edit (existing detail) a unit. */
export function OrgUnitFormDialog({ open, onOpenChange, type, parentId, existing }: {
  open: boolean; onOpenChange: (o: boolean) => void; type: OrgUnitType; parentId?: string | null; existing?: OrgUnitDetail;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const labels = useOrgLabels();
  const tree = useOrgTree();
  // One key per logical create. It is kept for retries after a network failure (the server may have processed
  // the request) and replaced once the server has answered, because a corrected form is a different request.
  const idempotencyKey = useRef<string>(crypto.randomUUID());
  const unit = existing?.unit;
  const editing = !!unit;
  const attributes = (unit?.attributes ?? {}) as Record<string, string>;
  const branding = (unit?.branding ?? {}) as Record<string, string>;
  const parents = useMemo(() => parentOptions(type, tree.data ?? []), [type, tree.data]);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      parentId: parentId ?? (MAY_BE_TOP_LEVEL[type] ? "" : parents[0]?.id ?? ""),
      code: unit?.code ?? "", name: unit?.name ?? "", shortName: unit?.shortName ?? "",
      city: attributes["city"] ?? "", state: attributes["state"] ?? "", website: attributes["website"] ?? "",
      email: attributes["email"] ?? "", accentColor: branding["accentColor"] ?? "",
    },
  });
  const [problem, setProblem] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: async (v: FormValues) => {
      const attrs = { city: v.city, state: v.state, website: v.website, email: v.email };
      const brand = isInstitution(type) ? { accentColor: v.accentColor } : undefined;
      if (editing) {
        return unwrap(await api.PATCH("/api/v1/org-units/{id}", {
          params: { path: { id: unit!.id! } },
          body: { version: unit!.version!, code: v.code, name: v.name, shortName: v.shortName, attributes: attrs, ...(brand ? { branding: brand } : {}) },
        }));
      }
      return unwrap(await api.POST("/api/v1/org-units", {
        headers: { "Idempotency-Key": idempotencyKey.current },
        body: { type, code: v.code, name: v.name, shortName: v.shortName, attributes: attrs,
          ...(v.parentId ? { parentId: v.parentId } : {}), ...(brand ? { branding: brand } : {}) },
      }));
    },
    onSuccess: (d) => {
      invalidate(qc);
      toast.show("success", editing ? "Changes saved" : `${labels.singular(type)} created`, d.unit?.name);
      onOpenChange(false);
      if (!editing && d.unit?.id) void navigate(`/organization/units/${d.unit.id}`);
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        idempotencyKey.current = crypto.randomUUID();
        if (e.code === "code_not_unique") { form.setError("code", { message: e.message }); return; }
        if (e.code === "invalid_hierarchy" || e.code === "invalid_parent" || e.code === "parent_inactive") {
          form.setError("parentId", { message: e.message }); return;
        }
        e.fieldErrors.forEach((fe) => form.setError(fe.field as keyof FormValues, { message: fe.message }));
      }
      setProblem(errorInfo(e).message);
    },
  });

  const err = form.formState.errors;
  const title = editing ? `Edit ${labels.singular(type).toLowerCase()}` : `New ${labels.singular(type).toLowerCase()}`;
  return (
    <Modal open={open} onOpenChange={onOpenChange} title={title}
      description={editing ? unit?.name : `Add a ${labels.singular(type).toLowerCase()} to your organization.`}
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={save.isPending}>Cancel</Button>
        <Button type="submit" form="org-unit-form" loading={save.isPending}>{editing ? "Save changes" : "Create"}</Button>
      </>}>
      <form id="org-unit-form" noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2"
        onSubmit={form.handleSubmit((v) => {
          setProblem(null);
          if (!editing && !MAY_BE_TOP_LEVEL[type] && !v.parentId) {
            form.setError("parentId", { message: `Choose a parent for this ${labels.singular(type).toLowerCase()}.` });
            return;
          }
          save.mutate(v);
        })}>
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        {!editing && (
          <Field label="Parent" className="sm:col-span-2" error={err.parentId?.message}
            hint={MAY_BE_TOP_LEVEL[type] ? `Leave empty for a stand-alone ${labels.singular(type).toLowerCase()}.` : undefined}>
            {(p) => (
              <Select {...p} {...form.register("parentId")} disabled={!!parentId}>
                {MAY_BE_TOP_LEVEL[type] && <option value="">None (top level)</option>}
                {!MAY_BE_TOP_LEVEL[type] && parents.length === 0 && <option value="">No eligible parent exists yet</option>}
                {parents.map((o) => <option key={o.id} value={o.id}>{o.name} ({labels.singular(o.type!)})</option>)}
              </Select>
            )}
          </Field>
        )}
        <Field label="Code" error={err.code?.message} hint="Unique among units at the same level">
          {(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}
        </Field>
        <Field label="Short name" error={err.shortName?.message}>{(p) => <Input {...p} {...form.register("shortName")} />}</Field>
        <Field label="Name" className="sm:col-span-2" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
        <Field label="City" error={err.city?.message}>{(p) => <Input {...p} {...form.register("city")} />}</Field>
        <Field label="State" error={err.state?.message}>{(p) => <Input {...p} {...form.register("state")} />}</Field>
        <Field label="Website" error={err.website?.message}>{(p) => <Input {...p} type="url" {...form.register("website")} />}</Field>
        <Field label="Contact email" error={err.email?.message}>{(p) => <Input {...p} type="email" {...form.register("email")} />}</Field>
        {isInstitution(type) && (
          <Field label="Accent colour" error={err.accentColor?.message} hint="Optional brand colour, e.g. #1D4ED8">
            {(p) => <Input {...p} placeholder="#1D4ED8" {...form.register("accentColor")} />}
          </Field>
        )}
      </form>
    </Modal>
  );
}

export function MoveDialog({ open, onOpenChange, detail }: { open: boolean; onOpenChange: (o: boolean) => void; detail: OrgUnitDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const labels = useOrgLabels();
  const tree = useOrgTree();
  const unit = detail.unit!;
  const type = unit.type!;
  const options = parentOptions(type, tree.data ?? [], unit.id).filter((o) => o.id !== unit.parentId);
  const [target, setTarget] = useState<string>(options[0]?.id ?? "");
  const [reason, setReason] = useState("");
  const move = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/org-units/{id}/move", {
      params: { path: { id: unit.id! } }, body: { version: unit.version!, ...(target ? { newParentId: target } : {}), reason },
    })),
    onSuccess: (d) => { invalidate(qc); toast.show("success", "Unit moved", `${d.unit?.name} now belongs to ${d.unit?.parentName ?? "the top level"}.`); onOpenChange(false); },
    onError: (e) => toast.show("danger", "Move not possible", errorInfo(e).message),
  });
  const canBeTop = MAY_BE_TOP_LEVEL[type] && !!unit.parentId;
  return (
    <ConfirmDialog open={open} onOpenChange={onOpenChange} title={`Move ${unit.name}`} confirmLabel="Move" busy={move.isPending}
      onConfirm={() => move.mutate()}
      description={<p>The {labels.singular(type).toLowerCase()} and all of its sub-units move together. Access granted on the old parent no longer applies to them.</p>}>
      <div className="mt-4 space-y-4">
        <Field label="New parent">
          {(p) => (
            <Select {...p} value={target} onChange={(e) => setTarget(e.target.value)}>
              {canBeTop && <option value="">None (top level)</option>}
              {!canBeTop && options.length === 0 && <option value="">No other eligible parent</option>}
              {options.map((o) => <option key={o.id} value={o.id}>{o.name} ({labels.singular(o.type!)})</option>)}
            </Select>
          )}
        </Field>
        <Field label="Reason (recorded in history)">{(p) => <TextArea {...p} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      </div>
    </ConfirmDialog>
  );
}

export function StatusDialog({ open, onOpenChange, detail }: { open: boolean; onOpenChange: (o: boolean) => void; detail: OrgUnitDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const unit = detail.unit!;
  const deactivate = unit.status === "ACTIVE";
  const [reason, setReason] = useState("");
  const change = useMutation({
    mutationFn: async () => unwrap(await api.POST(deactivate ? "/api/v1/org-units/{id}/deactivate" : "/api/v1/org-units/{id}/activate", {
      params: { path: { id: unit.id! } }, body: { version: unit.version!, reason },
    })),
    onSuccess: () => { invalidate(qc); toast.show("success", deactivate ? "Unit deactivated" : "Unit activated", unit.name); onOpenChange(false); },
    onError: (e) => toast.show("danger", deactivate ? "Cannot deactivate" : "Cannot activate", errorInfo(e).message),
  });
  return (
    <ConfirmDialog open={open} onOpenChange={onOpenChange} title={`${deactivate ? "Deactivate" : "Activate"} ${unit.name}?`}
      tone={deactivate ? "danger" : "primary"} confirmLabel={deactivate ? "Deactivate" : "Activate"} busy={change.isPending}
      onConfirm={() => change.mutate()}
      description={deactivate
        ? <p>Inactive units stay in the hierarchy and history but cannot receive new sub-units. {(detail.dependents?.activeChildren ?? 0) > 0 && <strong>Deactivate its {detail.dependents?.activeChildren} active sub-unit(s) first.</strong>}</p>
        : <p>The unit becomes available again. Its parent must be active.</p>}>
      <div className="mt-4"><Field label="Reason (recorded in history)">{(p) => <TextArea {...p} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field></div>
    </ConfirmDialog>
  );
}

export function DeleteDialog({ open, onOpenChange, detail }: { open: boolean; onOpenChange: (o: boolean) => void; detail: OrgUnitDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const unit = detail.unit!;
  const del = useMutation({
    mutationFn: async () => unwrap(await api.DELETE("/api/v1/org-units/{id}", { params: { path: { id: unit.id! }, query: { version: unit.version! } } })),
    onSuccess: () => {
      invalidate(qc);
      toast.show("success", "Unit deleted", `${unit.name} was removed. Its history is retained.`);
      onOpenChange(false);
      void navigate(unit.parentId ? `/organization/units/${unit.parentId}` : "/organization", { replace: true });
    },
    onError: (e) => toast.show("danger", "Cannot delete", errorInfo(e).message),
  });
  return (
    <ConfirmDialog open={open} onOpenChange={onOpenChange} title={`Delete ${unit.name}?`} tone="danger" confirmLabel="Delete permanently"
      busy={del.isPending} onConfirm={() => del.mutate()}
      description={<p>Only units that are not in use can be deleted. This cannot be undone; the change history is kept for audit.</p>} />
  );
}
