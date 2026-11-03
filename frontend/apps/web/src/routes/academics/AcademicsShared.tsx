import { ApiError } from "@ivorovi/api-client";
import { Alert, Badge, Button, ErrorState, Field, Input, Modal, Select, TextArea } from "@ivorovi/ui";
import { useQueryClient } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { errorInfo } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useOrgTree, type OrgNode } from "../../session/useOrgTree";
import { statusLabel, statusTone } from "./academicsModel";

export const PAGE_SIZE = 20;

/** Every academic query key starts with "academics" so one invalidation refreshes lists, details and KPIs. */
export function useInvalidateAcademics() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ["academics"] });
}

/** A fresh idempotency key per attempt (rotated after an error so a corrected retry is not replayed). */
export function useIdempotencyKey() {
  const key = useRef(crypto.randomUUID());
  return { get: () => key.current, rotate: () => { key.current = crypto.randomUUID(); } };
}

/** List state kept in the URL: search (debounced), filters, page and sort. */
export function useListParams(sortable: string[], defaultSort: string, defaultDirection: "asc" | "desc" = "asc") {
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const sort = sortable.includes(params.get("sort") ?? "") ? params.get("sort")! : defaultSort;
  const dirParam = params.get("dir");
  const direction: "asc" | "desc" = dirParam === "asc" || dirParam === "desc" ? dirParam : defaultDirection;
  const [search, setSearch] = useState(q);
  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);
  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v ? n.set(k, v) : n.delete(k))); return n; });
  const filter = (key: string) => params.get(key) ?? "";
  const tableSort = { sort: { key: sort, direction }, onSortChange: (s: { key: string; direction: "asc" | "desc" }) => update({ sort: s.key, dir: s.direction, page: null }) };
  const pager = (data: { totalPages?: number; totalElements?: number } | undefined) =>
    data ? { page, totalPages: data.totalPages ?? 0, totalElements: data.totalElements ?? 0, onPageChange: (p: number) => update({ page: String(p) }) } : undefined;
  return { q, page, sort, direction, search, setSearch, update, filter, tableSort, pager, filtered: [...params.keys()].some((k) => !["page", "sort", "dir"].includes(k)) };
}

export function SearchBox({ label, placeholder, value, onChange }: { label: string; placeholder: string; value: string; onChange: (v: string) => void }) {
  return (
    <label className="relative w-full sm:max-w-xs">
      <span className="sr-only">{label}</span>
      <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
      <Input type="search" placeholder={placeholder} value={value} maxLength={100} onChange={(e) => onChange(e.target.value)} className="pl-9" />
    </label>
  );
}

export function StatusBadge({ status }: { status: string | undefined }) {
  return <Badge tone={statusTone(status)}>{statusLabel(status)}</Badge>;
}

/** Filter select whose options are status values. */
export function StatusFilter({ value, onChange, statuses, className = "w-full sm:w-36" }: { value: string; onChange: (v: string) => void; statuses: string[]; className?: string }) {
  return (
    <Field label="Status" className={className}>
      {(p) => <Select {...p} value={value} onChange={(e) => onChange(e.target.value)}><option value="">All</option>
        {statuses.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}</Select>}
    </Field>
  );
}

/** 403/404 aware error state for detail pages. */
export function DetailError({ error, what, onRetry }: { error: unknown; what: string; onRetry: () => void }) {
  const status = error instanceof ApiError ? error.status : 0;
  if (status === 404) return <ErrorState title={`${what} not found`} message="It may not exist or may belong to another organization." />;
  if (status === 403) return <ErrorState title="Access denied" message={`This ${what.toLowerCase()} is outside the part of the organization you can access.`} />;
  return <ErrorState title={`Could not load this ${what.toLowerCase()}`} message={errorInfo(error).message} onRetry={onRetry} />;
}

/** Definition list used on detail pages. */
export function Facts({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2 xl:grid-cols-3">
      {items.map((i) => (
        <div key={i.label} className="min-w-0">
          <dt className="text-xs font-medium uppercase tracking-wide text-muted-strong">{i.label}</dt>
          <dd className="mt-1 break-words text-sm text-fg">{i.value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Status change confirmation. {@code reasonRequired}: the server refuses without one (corrections, cancellations,
 * deactivations that affect others). The reason is recorded in the audit trail.
 */
export function StatusDialog({ open, onOpenChange, title, description, confirmLabel, tone = "primary", reasonRequired, reasonLabel = "Reason",
  busy, error, onConfirm }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: string; description: ReactNode; confirmLabel: string; tone?: "primary" | "danger";
  reasonRequired: boolean; reasonLabel?: string; busy: boolean; error: string | null; onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const missing = reasonRequired && reason.trim().length === 0;
  return (
    <Modal open={open} onOpenChange={(o) => { if (!o) { setReason(""); setTouched(false); } onOpenChange(o); }} title={title} description={description}
      footer={<>
        <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
        <Button variant={tone === "danger" ? "danger" : "primary"} loading={busy} onClick={() => { setTouched(true); if (!missing) onConfirm(reason.trim()); }}>{confirmLabel}</Button>
      </>}>
      {error && <Alert tone="danger" className="mb-4">{error}</Alert>}
      <Field label={reasonRequired ? reasonLabel : `${reasonLabel} (optional)`} error={touched && missing ? "Give a reason." : undefined}
        hint="Recorded in the audit trail.">
        {(p) => <TextArea {...p} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
      </Field>
    </Modal>
  );
}

/** Maps a server error code to a form field when one fits, otherwise returns the message for a page-level alert. */
export function fieldError(e: unknown, fields: Record<string, string>, setError: (field: string, message: string) => void): string | null {
  if (e instanceof ApiError && e.code && fields[e.code]) { setError(fields[e.code]!, e.message); return null; }
  return errorInfo(e).message;
}

/**
 * Active units of the given types where the caller holds {@code permission} (inherited from ancestors), indented by
 * depth; {@code within} limits the list to a subtree. "Whole organization" only with the tenant-wide permission.
 */
export function UnitChoices({ permission, types, within, organizationLabel }: {
  permission: string; types: string[]; within?: string | null | undefined; organizationLabel?: string;
}) {
  const tree = useOrgTree();
  const labels = useOrgLabels();
  const { access } = useSession();
  const options = useMemo(() => {
    const nodes = (tree.data ?? []).filter((n) => n.status === "ACTIVE");
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const ancestors = (n: OrgNode): string[] => (n.parentId && byId.has(n.parentId) ? [...ancestors(byId.get(n.parentId)!), n.parentId] : []);
    return nodes.filter((n) => types.includes(n.type) && access.hasInOrgUnit(permission, n.id, ancestors(n)))
      .filter((n) => !within || n.id === within || ancestors(n).includes(within))
      .map((n) => ({ n, d: ancestors(n).length }));
  }, [tree.data, access, permission, types, within]);
  return (
    <>
      {organizationLabel && access.has(permission) && <option value="">{organizationLabel}</option>}
      {options.map(({ n, d }) => (
        <option key={n.id} value={n.id}>{"\u00a0".repeat(d * 2)}{n.name} ({labels.singular(n.type)})</option>
      ))}
    </>
  );
}
