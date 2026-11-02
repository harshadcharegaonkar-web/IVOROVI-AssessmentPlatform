import { AlertTriangle, CheckCircle2, Info, Inbox, XCircle } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "../lib/cn";
import { Button } from "./Button";

export function EmptyState({ title, description, action }: { title: string; description?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <Inbox aria-hidden className="h-8 w-8 text-muted" />
      <p className="font-medium text-fg">{title}</p>
      {description && <p className="max-w-md text-sm text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ title = "Something went wrong", message, traceId, onRetry }: {
  title?: string;
  message?: string;
  traceId?: string | undefined;
  onRetry?: () => void;
}) {
  return (
    <div role="alert" className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <XCircle aria-hidden className="h-8 w-8 text-danger" />
      <p className="font-medium text-fg">{title}</p>
      {message && <p className="max-w-md text-sm text-muted">{message}</p>}
      {traceId && <p className="text-xs text-muted">Reference: <code>{traceId}</code></p>}
      {onRetry && <Button variant="secondary" size="sm" className="mt-2" onClick={onRetry}>Try again</Button>}
    </div>
  );
}

type AlertTone = "info" | "success" | "warning" | "danger";
const alertStyles: Record<AlertTone, { cls: string; Icon: typeof Info }> = {
  info: { cls: "bg-info-soft text-fg", Icon: Info },
  success: { cls: "bg-success-soft text-fg", Icon: CheckCircle2 },
  warning: { cls: "bg-warning-soft text-fg", Icon: AlertTriangle },
  danger: { cls: "bg-danger-soft text-fg", Icon: XCircle },
};

export function Alert({ tone = "info", title, children, className }: { tone?: AlertTone; title?: string; children?: ReactNode; className?: string }) {
  const { cls, Icon } = alertStyles[tone];
  return (
    <div role={tone === "danger" ? "alert" : "status"} className={cn("flex gap-3 rounded-md border border-border px-4 py-3 text-sm", cls, className)}>
      <Icon aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
      <div>
        {title && <p className="font-medium">{title}</p>}
        {children && <div className="text-muted">{children}</div>}
      </div>
    </div>
  );
}
