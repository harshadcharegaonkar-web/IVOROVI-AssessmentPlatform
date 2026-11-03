import { unwrap } from "@ivorovi/api-client";
import { Button, cn, ErrorState, Skeleton } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Bell, CheckCircle2, Info, XCircle } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatRelative } from "../lib/format";
import { NotificationPopover } from "./LazyShell";

const icons = { INFO: Info, SUCCESS: CheckCircle2, WARNING: AlertTriangle, ERROR: XCircle } as const;
const tones = { INFO: "text-primary", SUCCESS: "text-success", WARNING: "text-warning", ERROR: "text-danger" } as const;

export function NotificationCenter({ buttonClassName }: { buttonClassName?: string }) {
  const api = useApi();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const list = useQuery({
    queryKey: ["notifications"],
    refetchInterval: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/notifications", { params: { query: { size: 10 } } })),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["notifications"] });
  const markRead = useMutation({
    mutationFn: async (id: string) => unwrap(await api.POST("/api/v1/notifications/{id}/read", { params: { path: { id } } })),
    onSuccess: refresh,
  });
  const markAll = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/notifications/read-all")),
    onSuccess: refresh,
  });
  const unread = list.data?.unread ?? 0;

  return (
    <NotificationPopover open={open} onOpenChange={setOpen} trigger={(p) => (
      <button type="button" aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"} {...p} className={cn("relative", buttonClassName)}>
        <Bell aria-hidden className="h-5 w-5" strokeWidth={1.75} />
        {unread > 0 && (
          <span aria-hidden data-testid="notification-count" className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-white">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>
    )}>
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <p className="font-semibold">Notifications</p>
        <Button variant="ghost" size="sm" disabled={!unread} loading={markAll.isPending} onClick={() => markAll.mutate()}>Mark all as read</Button>
      </div>
      <div className="max-h-96 overflow-y-auto">
        {list.isPending ? <div className="space-y-3 p-4"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          : list.isError ? <ErrorState message={errorInfo(list.error).message} onRetry={() => void list.refetch()} />
          : (list.data.items ?? []).length === 0 ? (
            <div className="px-4 py-10 text-center">
              <CheckCircle2 aria-hidden className="mx-auto h-8 w-8 text-success" />
              <p className="mt-2 font-medium">You're all caught up</p>
              <p className="text-sm text-muted">Import results, invitations and alerts will appear here.</p>
            </div>
          ) : (
            <ul>
              {list.data.items!.map((n) => {
                const Icon = icons[n.severity as keyof typeof icons] ?? Info;
                return (
                  <li key={n.id}>
                    <button type="button" className={cn("flex w-full gap-3 border-b border-border px-4 py-3 text-left hover:bg-surface-muted", !n.readAt && "bg-primary-soft/40")}
                      onClick={() => {
                        if (!n.readAt) markRead.mutate(n.id!);
                        setOpen(false);
                        if (n.link) void navigate(n.link);
                      }}>
                      <Icon aria-hidden className={cn("mt-0.5 h-5 w-5 shrink-0", tones[n.severity as keyof typeof tones])} />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-baseline justify-between gap-2">
                          <span className={cn("truncate", !n.readAt ? "font-semibold" : "font-medium")}>{n.title}</span>
                          <span className="shrink-0 text-xs text-muted">{formatRelative(n.createdAt)}</span>
                        </span>
                        {n.body && <span className="mt-0.5 line-clamp-2 block text-muted">{n.body}</span>}
                      </span>
                      {!n.readAt && <span className="sr-only">Unread</span>}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
      </div>
    </NotificationPopover>
  );
}
