import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type PermissionDomain, type RoleDetail } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, ErrorState, Field, Input, Link, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, ChevronLeft, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { ancestorIds, useOrgTree } from "../../session/useOrgTree";
import { UnitOptions } from "../people/PeopleDialogs";
import { effectiveAccess, LEVEL, selectable } from "./accessModel";

const schema = z.object({
  name: z.string().trim().min(1, "Name is required").max(100),
  code: z.string().trim().max(63).refine((v) => !v || /^[A-Za-z][A-Za-z0-9_]{1,62}$/.test(v), "Letters, digits and underscores, starting with a letter"),
  description: z.string().trim().max(1000),
  scope: z.string(),
});
type Values = z.infer<typeof schema>;

/** Create a role (optionally cloned from another) or edit a custom role. */
export function RoleEditorPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const from = params.get("from");
  const api = useApi();
  const catalogue = useQuery({ queryKey: ["permissions"], staleTime: 5 * 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/permissions")) });
  const sourceId = id ?? from;
  const source = useQuery({ queryKey: ["role", sourceId], enabled: !!sourceId,
    queryFn: async () => unwrap(await api.GET("/api/v1/roles/{id}", { params: { path: { id: sourceId! } } })) });
  if (catalogue.isPending || (sourceId && source.isPending)) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-96 w-full" /></>;
  if (catalogue.isError) return <ErrorState message={errorInfo(catalogue.error).message} onRetry={() => void catalogue.refetch()} />;
  if (source.isError) return <ErrorState title="Role not found" message={errorInfo(source.error).message} />;
  if (id && source.data && (source.data.role?.system || !source.data.canEdit)) {
    return <ErrorState title="This role cannot be edited" message={source.data.role?.system
      ? "System roles are defined by Ivorovi. Clone the role to create an editable copy." : "You do not have permission to edit this role."} />;
  }
  return <Editor key={sourceId ?? "new"} catalogue={catalogue.data} existing={id ? source.data : undefined} cloneOf={from ? source.data : undefined} />;
}

function Editor({ catalogue, existing, cloneOf }: { catalogue: PermissionDomain[]; existing?: RoleDetail; cloneOf?: RoleDetail }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const editing = !!existing;
  const base = existing ?? cloneOf;
  const key = useRef(crypto.randomUUID());
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: existing?.role?.name ?? (cloneOf ? `${cloneOf.role?.name} (copy)` : ""),
      code: "", description: base?.role?.description ?? "",
      scope: existing?.role?.scopeOrgUnitId ?? cloneOf?.role?.scopeOrgUnitId ?? "",
    },
  });
  const scope = useWatch({ control: form.control, name: "scope" });
  // without organization-wide role.manage the first selectable unit is the only valid default (the select shows it)
  const { access } = useSession();
  const tree = useOrgTree();
  useEffect(() => {
    if (editing || scope || access.has("role.manage") || !tree.data) return;
    const first = tree.data.find((n) => n.status === "ACTIVE" && access.hasInOrgUnit("role.manage", n.id, ancestorIds(tree.data, n.id)));
    if (first) form.setValue("scope", first.id);
  }, [editing, scope, access, tree.data, form]);
  const unitLimited = editing ? !!existing!.role?.scopeOrgUnitId : !!scope;
  const all = useMemo(() => catalogue.flatMap((d) => d.permissions ?? []), [catalogue]);
  const [selected, setSelected] = useState<Set<string>>(() => {
    const initial = new Set(base?.permissions ?? []);
    if (!editing) for (const p of all) if (initial.has(p.code!) && !selectable(p, !!(cloneOf?.role?.scopeOrgUnitId)).ok) initial.delete(p.code!);
    return initial;
  });
  const original = useMemo(() => new Set(existing?.permissions ?? []), [existing]);
  const droppedFromClone = cloneOf ? (cloneOf.permissions ?? []).filter((c) => !selected.has(c)) : [];
  const summary = effectiveAccess(catalogue, selected);
  const added = [...selected].filter((c) => !original.has(c));
  const removed = [...original].filter((c) => !selected.has(c));
  const holders = existing?.role?.activeAssignments ?? 0;

  const toggle = (code: string, on: boolean) => setSelected((s) => { const n = new Set(s); if (on) n.add(code); else n.delete(code); return n; });
  const liveDomains = catalogue.filter((d) => (d.permissions ?? []).some((p) => p.availability !== "PLANNED"));
  const plannedDomains = catalogue.filter((d) => (d.permissions ?? []).every((p) => p.availability === "PLANNED"));
  const renderDomain = (d: PermissionDomain) => {
    const perms = d.permissions ?? [];
    const available = perms.filter((p) => selectable(p, unitLimited).ok);
    const chosen = perms.filter((p) => selected.has(p.code!)).length;
    return (
      <fieldset key={d.domain} className="px-5 py-4 sm:px-6">
        <legend className="sr-only">{d.label}</legend>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-sm font-semibold text-fg">{d.label} {chosen > 0 && <span className="font-normal text-muted">· {chosen} selected</span>}</h3>
          {available.length > 1 && (
            <button type="button" className="text-xs font-medium text-primary hover:underline"
              onClick={() => setSelected((s) => { const n = new Set(s); const allOn = available.every((p) => n.has(p.code!));
                available.forEach((p) => (allOn ? n.delete(p.code!) : n.add(p.code!))); return n; })}>
              {available.every((p) => selected.has(p.code!)) ? "Clear all" : "Select all"}
            </button>
          )}
        </div>
        <ul className="space-y-2">
          {perms.map((p) => {
            const s = selectable(p, unitLimited);
            const checked = selected.has(p.code!);
            const keepable = checked && original.has(p.code!);       // editing: keep existing even if not held
            const disabled = !s.ok && !keepable;
            return (
              <li key={p.code}>
                <label className={`flex items-start gap-3 rounded-lg border p-3 text-sm ${disabled ? "border-border bg-bg" : "cursor-pointer border-border hover:border-primary"} ${checked ? "border-primary bg-primary-soft" : ""}`}>
                  <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--color-primary)]" checked={checked} disabled={disabled}
                    onChange={(e) => toggle(p.code!, e.target.checked)} aria-describedby={`perm-${p.code}`} />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className={`font-medium ${disabled ? "text-muted-strong" : "text-fg"}`}>{p.name}</span>
                      <Badge tone={LEVEL[p.level ?? "READ"]!.tone}>{LEVEL[p.level ?? "READ"]!.label}</Badge>
                      {p.scope === "ORGANIZATION" && <Badge>Organization-wide</Badge>}
                      {p.availability === "PLANNED" && <Badge>Coming later</Badge>}
                    </span>
                    <span id={`perm-${p.code}`} className="mt-0.5 block text-muted-strong">{p.description}</span>
                    <span className="mt-0.5 block font-mono text-xs text-muted-strong">{p.code}{disabled && s.reason ? ` · ${s.reason}` : ""}</span>
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      </fieldset>
    );
  };

  const save = useMutation({
    mutationFn: async (v: Values) => {
      const permissions = all.map((p) => p.code!).filter((c) => selected.has(c));
      if (editing) {
        return unwrap(await api.PATCH("/api/v1/roles/{id}", { params: { path: { id: existing!.role!.id! } },
          body: { version: existing!.role!.version!, name: v.name, description: v.description, permissions } }));
      }
      return unwrap(await api.POST("/api/v1/roles", { headers: { "Idempotency-Key": key.current },
        body: { name: v.name, description: v.description, permissions, ...(v.code ? { code: v.code.toUpperCase() } : {}),
          ...(v.scope ? { scopeOrgUnitId: v.scope } : {}), ...(cloneOf ? { clonedFromRoleId: cloneOf.role!.id! } : {}) } }));
    },
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ["roles"] });
      void qc.invalidateQueries({ queryKey: ["role"] });
      void qc.invalidateQueries({ queryKey: ["users"] });
      void qc.invalidateQueries({ queryKey: ["user"] });
      toast.show("success", editing ? "Role saved" : cloneOf ? "Role cloned" : "Role created",
        editing && holders > 0 ? `Access changed for ${holders} assignment(s).` : d.role?.name);
      void navigate(`/access/roles/${d.role?.id}`);
    },
    onError: (e) => {
      setConfirming(false);
      if (e instanceof ApiError) {
        key.current = crypto.randomUUID();
        if (e.code === "role_code_in_use" || e.code === "reserved_role_code" || e.code === "invalid_role_code") { form.setError(e.code === "role_code_in_use" && !form.getValues("code") ? "name" : "code", { message: e.message }); return; }
        if (e.code === "invalid_org_unit") { form.setError("scope", { message: e.message }); return; }
      }
      setProblem(errorInfo(e).message);
    },
  });

  const submit = form.handleSubmit((v) => {
    setProblem(null);
    if (selected.size === 0) { setProblem("Choose at least one permission."); return; }
    if (editing && holders > 0 && (added.length > 0 || removed.length > 0)) { setConfirming(true); return; }
    save.mutate(v);
  });
  const err = form.formState.errors;
  const title = editing ? `Edit ${existing!.role!.name}` : cloneOf ? `Clone ${cloneOf.role!.name}` : "New role";
  const back = editing ? `/access/roles/${existing!.role!.id}` : cloneOf ? `/access/roles/${cloneOf.role!.id}` : "/access/roles";

  return (
    <>
      <Link to={back} className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg"><ChevronLeft aria-hidden className="h-4 w-4" /> Back</Link>
      <PageHeader title={title} description="Choose what people with this role can do. You can only include permissions you hold yourself at the role's scope." />
      <form noValidate onSubmit={submit} className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="min-w-0 space-y-6">
          {problem && <Alert tone="danger">{problem}</Alert>}
          {cloneOf && droppedFromClone.length > 0 && (
            <Alert tone="warning" title={`${droppedFromClone.length} permission(s) of ${cloneOf.role?.name} were left out`}>
              You do not hold them at this scope, or they cannot be part of this kind of role: {droppedFromClone.join(", ")}.
            </Alert>
          )}
          <Card>
            <CardHeader title="Role details" />
            <CardBody className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Name" error={err.name?.message} hint="Shown to administrators when assigning roles">{(p) => <Input {...p} {...form.register("name")} />}</Field>
              {!editing ? (
                <Field label="Code (optional)" error={err.code?.message} hint="Generated from the name when empty">{(p) => <Input {...p} autoComplete="off" placeholder="e.g. EXAM_CELL_ASSISTANT" {...form.register("code")} />}</Field>
              ) : (
                <Field label="Code">{(p) => <Input {...p} value={existing!.role!.code} disabled readOnly />}</Field>
              )}
              <Field label="Description" className="sm:col-span-2" error={err.description?.message}>{(p) => <TextArea {...p} maxLength={1000} rows={2} {...form.register("description")} />}</Field>
              <Field label="Where can this role be used?" className="sm:col-span-2" error={err.scope?.message}
                hint={editing ? "The scope of an existing role cannot change; clone it to use a different scope." : "A role limited to a unit can only be assigned there and below, and cannot contain organization-wide permissions."}>
                {(p) => (
                  <Select {...p} {...form.register("scope")} disabled={editing}>
                    <UnitOptions permission="role.manage" />
                  </Select>
                )}
              </Field>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Permissions" description="Grouped by area. View = read-only, Act = do a task, Manage = create, change or remove." />
            <div className="divide-y divide-border" data-testid="permission-configurator">
              {liveDomains.map(renderDomain)}
              {plannedDomains.length > 0 && (
                <details className="px-5 py-4 sm:px-6" data-testid="planned-permissions">
                  <summary className="cursor-pointer text-sm font-semibold text-fg">Coming with later modules ({plannedDomains.length} areas)</summary>
                  <p className="mt-2 text-sm text-muted-strong">These permissions are listed so you can plan roles; they become selectable when their module is released.</p>
                  <div className="mt-2 -mx-5 divide-y divide-border sm:-mx-6">{plannedDomains.map(renderDomain)}</div>
                </details>
              )}
            </div>
          </Card>
        </div>

        <aside className="min-w-0 xl:sticky xl:top-20 xl:self-start">
          <Card data-testid="effective-access-summary">
            <CardHeader title="Effective access" description={`${summary.count} permission(s) selected`} />
            <CardBody className="space-y-4 text-sm">
              {summary.count === 0 ? <p className="text-muted">Select permissions to see what people with this role can do.</p> : (
                <>
                  <p className="text-fg">People with this role can, {unitLimited ? "within the unit the role is limited to and everything below it" : "for the organization or for the unit it is assigned to"}:</p>
                  {summary.domains.map((d) => (
                    <div key={d.label}>
                      <p className="text-xs font-medium uppercase tracking-wider text-muted-strong">{d.label}</p>
                      <ul className="mt-1 list-disc space-y-0.5 pl-5">{d.permissions.map((p) => <li key={p.code}>{p.name}</li>)}</ul>
                    </div>
                  ))}
                </>
              )}
              {summary.warnings.map((w) => (
                <p key={w} className="flex gap-2 rounded-lg bg-warning-soft p-2.5 text-xs text-warning-text"><AlertTriangle aria-hidden className="mt-0.5 h-3.5 w-3.5 shrink-0" />{w}</p>
              ))}
              {editing && (added.length > 0 || removed.length > 0) && (
                <div className="rounded-lg border border-border p-3 text-xs">
                  <p className="font-medium text-fg">Changes</p>
                  {added.length > 0 && <p className="mt-1 text-success-text">Added: {added.join(", ")}</p>}
                  {removed.length > 0 && <p className="mt-1 text-danger-text">Removed: {removed.join(", ")}</p>}
                  {holders > 0 && <p className="mt-1 text-muted-strong">Applies immediately to {holders} assignment(s).</p>}
                </div>
              )}
              <div className="flex flex-col gap-2 pt-2">
                <Button type="submit" loading={save.isPending}><ShieldCheck aria-hidden className="h-4 w-4" /> {editing ? "Save role" : cloneOf ? "Create clone" : "Create role"}</Button>
                <Button type="button" variant="secondary" onClick={() => void navigate(back)}>Cancel</Button>
              </div>
            </CardBody>
          </Card>
        </aside>
      </form>
      {confirming && (
        <ConfirmDialog open onOpenChange={(o) => !o && setConfirming(false)} title={`Change access for ${holders} assignment(s)?`} confirmLabel="Save role"
          busy={save.isPending} onConfirm={() => form.handleSubmit((v) => save.mutate(v))()}
          description={<p>Everyone holding <strong>{existing!.role!.name}</strong> gets the new permissions on their next action.{added.length > 0 && <> Added: {added.join(", ")}.</>}{removed.length > 0 && <> Removed: {removed.join(", ")}.</>}</p>} />
      )}
    </>
  );
}

export default RoleEditorPage;
