export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function formatRelative(iso: string | undefined | null, now: Date = new Date()): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const s = Math.round((now.getTime() - d.getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  if (s < 7 * 86400) return `${Math.floor(s / 86400)} d ago`;
  return d.toLocaleDateString(undefined, { dateStyle: "medium" });
}

export function humanize(code: string | undefined | null): string {
  if (!code) return "—";
  return code.toLowerCase().replace(/[_.]/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

export function formatNumber(n: number | undefined | null): string {
  return n === undefined || n === null ? "—" : new Intl.NumberFormat("en-IN").format(n);
}
