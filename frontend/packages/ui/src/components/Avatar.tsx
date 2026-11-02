import { cn } from "../lib/cn";

/** Initials avatar (no photos). Decorative: always paired with a visible name. */
export function Avatar({ name, size = "md", className }: { name: string; size?: "sm" | "md"; className?: string }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]!.toUpperCase()).join("") || "?";
  return (
    <span aria-hidden className={cn(
      "inline-flex shrink-0 items-center justify-center rounded-full bg-primary-soft font-semibold text-primary",
      size === "sm" ? "h-8 w-8 text-xs" : "h-9 w-9 text-sm", className)}>
      {initials}
    </span>
  );
}
