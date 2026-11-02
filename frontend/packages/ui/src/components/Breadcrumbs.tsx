import { ChevronRight } from "lucide-react";
import { Link } from "./link";

export interface Crumb { label: string; to?: string | undefined }

/** Last crumb is the current page and is not a link. */
export function Breadcrumbs({ items, className }: { items: Crumb[]; className?: string }) {
  if (items.length === 0) return null;
  return (
    <nav aria-label="Breadcrumb" className={className}>
      <ol className="flex min-w-0 items-center gap-1 text-sm">
        {items.map((c, i) => {
          const last = i === items.length - 1;
          return (
            <li key={`${c.label}-${i}`} className="flex min-w-0 items-center gap-1">
              {i > 0 && <ChevronRight aria-hidden className="h-4 w-4 shrink-0 text-muted" />}
              {last || !c.to ? (
                <span aria-current={last ? "page" : undefined} className={last ? "truncate font-medium text-fg" : "truncate text-muted"}>{c.label}</span>
              ) : (
                <Link to={c.to} className="truncate text-muted hover:text-fg">{c.label}</Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
