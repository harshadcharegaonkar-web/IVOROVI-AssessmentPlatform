export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function humanize(code: string | undefined | null): string {
  if (!code) return "—";
  return code.toLowerCase().replace(/[_.]/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}
