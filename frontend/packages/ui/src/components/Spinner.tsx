import { cn } from "../lib/cn";

export function Spinner({ size = "md", label = "Loading", className }: { size?: "sm" | "md" | "lg"; label?: string; className?: string }) {
  const dims = { sm: "h-4 w-4 border-2", md: "h-6 w-6 border-2", lg: "h-10 w-10 border-3" }[size];
  return (
    <span role={label ? "status" : undefined} className={cn("inline-flex items-center gap-2", className)}>
      <span aria-hidden className={cn("animate-spin rounded-full border-current border-r-transparent", dims)} />
      {label && <span className="sr-only">{label}</span>}
    </span>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden className={cn("block animate-pulse rounded bg-surface-muted", className)} />;
}
