import { Alert, Badge, type BadgeTone, Button, TabPanel, Tabs } from "@ivorovi/ui";
import { Copy } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";

export const statusTone: Record<string, BadgeTone> = {
  HEALTHY: "success", ACTIVE: "success", SUCCEEDED: "success", DELIVERED: "success", EXPIRING: "warning",
  FAILING: "danger", FAILED: "danger", AUTO_DISABLED: "danger", REVOKED: "neutral", EXPIRED: "neutral",
  UNKNOWN: "neutral", PAUSED: "warning", PENDING: "info", RUNNING: "info", SUSPENDED: "warning", PENDING_APPROVAL: "info", DISABLED: "neutral",
};

export function StatusBadge({ status }: { status?: string | null }) {
  const s = status ?? "UNKNOWN";
  return <Badge tone={statusTone[s] ?? "neutral"}>{s.replaceAll("_", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}</Badge>;
}

/** A credential shown exactly once (API key or signing secret): copy it now, it cannot be retrieved later. */
export function SecretOnce({ label, value, children }: { label: string; value: string; children?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  return (
    <Alert tone="warning" title={`${label} — shown only once`}>
      <p>Copy it now and store it in your secret manager. It is not stored in readable form and cannot be shown again.</p>
      <div className="mt-2 flex items-center gap-2">
        <code data-testid="secret-once" className="min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded-md border border-border bg-surface px-2 py-1.5 font-mono text-xs text-fg">{value}</code>
        <Button variant="secondary" size="sm" onClick={() => { void navigator.clipboard?.writeText(value); setCopied(true); }}>
          <Copy aria-hidden className="h-4 w-4" /> {copied ? "Copied" : "Copy"}
        </Button>
      </div>
      {children}
    </Alert>
  );
}

/** Integrations / API clients / Webhooks section switcher. */
export function IntegrationTabs({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const value = ["/integrations/api-clients", "/integrations/service-identities", "/integrations/webhooks"].find((p) => pathname.startsWith(p)) ?? "/integrations";
  return (
    <Tabs label="Integration areas" value={value} onValueChange={(v) => void navigate(v)}
      tabs={[{ value: "/integrations", label: "Integrations" }, { value: "/integrations/api-clients", label: "API clients" },
        { value: "/integrations/service-identities", label: "Service identities" }, { value: "/integrations/webhooks", label: "Webhooks" }]}>
      <TabPanel value={value}>{children}</TabPanel>
    </Tabs>
  );
}

export const dt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : "—");
