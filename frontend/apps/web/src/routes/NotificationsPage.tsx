import { unwrap, type Notification } from "@ivorovi/api-client";
import { Badge, Button, Card, EmptyState, ErrorState, Link, PageHeader, Pagination, Skeleton, TabPanel, Tabs, cn } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Info, XCircle } from "lucide-react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatRelative, humanize } from "../lib/format";

const CATEGORIES = ["invitations", "accounts", "access", "students", "imports", "integrations", "system"] as const;
const icons = { INFO: Info, SUCCESS: CheckCircle2, WARNING: AlertTriangle, ERROR: XCircle } as const;
const tones = { INFO: "text-primary", SUCCESS: "text-success", WARNING: "text-warning", ERROR: "text-danger" } as const;

/** Notification center: full history with read state and category filters, deep links and delivery status. */
export function NotificationsPage() {
  const api = useApi();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const state = params.get("state") ?? "ALL";
  const category = params.get("category") ?? "";
  const page = Number(params.get("page") ?? 0);
  const set = (k: string, v: string | null) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== "page") n.delete("page"); return n; }, { replace: true });
  const q = useQuery({
    queryKey: ["notifications", "history", state, category, page], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/notifications", { params: { query: { state: state as "ALL", page, size: 20, ...(category ? { category } : {}) } } })),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["notifications"] });
  const toggle = useMutation({
    mutationFn: async (n: Notification) => n.readAt
      ? unwrap(await api.POST("/api/v1/notifications/{id}/unread", { params: { path: { id: n.id! } } }))
      : unwrap(await api.POST("/api/v1/notifications/{id}/read", { params: { path: { id: n.id! } } })),
    onSuccess: refresh,
  });
  const readAll = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/notifications/read-all")), onSuccess: refresh });
  const byCat = q.data?.unreadByCategory ?? {};
  return (
    <>
      <PageHeader title="Notifications" description="In-app notifications about invitations, accounts, access changes, students, imports and integrations."
        actions={<Button onClick={() => readAll.mutate()} loading={readAll.isPending} disabled={!q.data?.unread}>Mark all as read</Button>} />
      <Tabs label="Read state" value={state} onValueChange={(v) => set("state", v === "ALL" ? null : v)}
        tabs={[{ value: "ALL", label: "All" }, { value: "UNREAD", label: "Unread", count: q.data?.unread }, { value: "READ", label: "Read" }]}>
        <TabPanel value={state}>
        <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Category">
          {["", ...CATEGORIES].map((c) => (
            <Button key={c || "all"} size="sm" variant={category === c ? "primary" : "secondary"} aria-pressed={category === c} onClick={() => set("category", c || null)}>
              {c ? humanize(c) : "All categories"}{c && byCat[c] ? ` (${byCat[c]})` : ""}
            </Button>
          ))}
        </div>
        <Card>
          {q.isError ? <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />
            : q.isPending ? <div className="space-y-3 p-6">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-12 w-full" />)}</div>
            : (q.data.items ?? []).length === 0 ? <EmptyState title="No notifications" description="Nothing matches this view." /> : (
              <ul className="divide-y divide-border" data-testid="notification-history">
                {q.data.items!.map((n) => {
                  const Icon = icons[n.severity as keyof typeof icons] ?? Info;
                  return (
                    <li key={n.id} className={cn("flex items-start gap-3 px-5 py-4 sm:px-6", !n.readAt && "bg-primary-soft/40")}>
                      <Icon aria-hidden className={cn("mt-0.5 h-5 w-5 shrink-0", tones[n.severity as keyof typeof tones])} />
                      <div className="min-w-0 flex-1">
                        <p className={cn("text-sm", !n.readAt ? "font-semibold" : "font-medium")}>
                          {n.link ? <Link to={n.link} className="hover:underline" onClick={() => { if (!n.readAt) toggle.mutate(n); }}>{n.title}</Link> : n.title}
                          {!n.readAt && <span className="sr-only"> (unread)</span>}
                        </p>
                        {n.body && <p className="mt-0.5 text-sm text-muted">{n.body}</p>}
                        <p className="mt-1 flex flex-wrap gap-2 text-xs text-muted">
                          <Badge>{humanize(n.category)}</Badge>
                          <span>{formatRelative(n.createdAt)}</span>
                          <span>· {humanize(n.channel)} · {humanize(n.deliveryStatus)}</span>
                        </p>
                      </div>
                      <Button size="sm" variant="ghost" onClick={() => toggle.mutate(n)}>{n.readAt ? "Mark unread" : "Mark read"}</Button>
                    </li>
                  );
                })}
              </ul>
            )}
          {q.data && (q.data.totalPages ?? 0) > 1 && <Pagination page={page} totalPages={q.data.totalPages!} totalElements={q.data.totalElements!} onPageChange={(p) => set("page", String(p))} />}
        </Card>
        </TabPanel>
      </Tabs>
    </>
  );
}

export default NotificationsPage;
