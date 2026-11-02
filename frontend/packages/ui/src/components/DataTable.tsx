import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";
import type { ReactNode } from "react";
import { cn, focusRing } from "../lib/cn";
import { Pagination } from "./Pagination";
import { Skeleton } from "./Spinner";
import { EmptyState, ErrorState } from "./States";

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  sortable?: boolean;
  className?: string;
  /** Hidden below the md breakpoint to keep tables usable on phones. */
  hideOnMobile?: boolean;
}

export interface SortState {
  key: string;
  direction: "asc" | "desc";
}

export interface DataTableProps<T> {
  caption: string;
  columns: Column<T>[];
  rows: T[] | undefined;
  rowKey: (row: T) => string;
  loading?: boolean;
  error?: { message: string; traceId?: string | undefined } | null;
  onRetry?: () => void;
  sort?: SortState;
  onSortChange?: (sort: SortState) => void;
  page?: { page: number; totalPages: number; totalElements: number; onPageChange: (page: number) => void };
  empty?: { title: string; description?: ReactNode; action?: ReactNode };
}

/** Accessible data table with sorting, pagination and loading / empty / error states. */
export function DataTable<T>({ caption, columns, rows, rowKey, loading, error, onRetry, sort, onSortChange, page, empty }: DataTableProps<T>) {
  if (error) return <ErrorState message={error.message} traceId={error.traceId} {...(onRetry ? { onRetry } : {})} />;

  return (
    <div>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="bg-surface-muted text-xs font-medium uppercase tracking-wider text-muted">
            <tr>
              {columns.map((c) => {
                const active = sort?.key === c.key;
                const ariaSort = active ? (sort.direction === "asc" ? "ascending" : "descending") : c.sortable ? "none" : undefined;
                return (
                  <th key={c.key} scope="col" aria-sort={ariaSort} className={cn("h-10 px-4 font-medium sm:px-6", c.hideOnMobile && "hidden md:table-cell", c.className)}>
                    {c.sortable && onSortChange ? (
                      <button
                        type="button"
                        className={cn("inline-flex items-center gap-1 rounded uppercase", focusRing)}
                        onClick={() => onSortChange({ key: c.key, direction: active && sort.direction === "asc" ? "desc" : "asc" })}
                      >
                        {c.header}
                        {active ? (sort.direction === "asc" ? <ArrowUp aria-hidden className="h-3 w-3" /> : <ArrowDown aria-hidden className="h-3 w-3" />) : <ArrowUpDown aria-hidden className="h-3 w-3 opacity-50" />}
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody aria-busy={loading || undefined}>
            {loading && !rows
              ? Array.from({ length: 5 }, (_, i) => (
                  <tr key={i} className="border-t border-border">
                    {columns.map((c) => (
                      <td key={c.key} className={cn("px-4 py-3", c.hideOnMobile && "hidden md:table-cell")}><Skeleton className="h-4 w-full max-w-48" /></td>
                    ))}
                  </tr>
                ))
              : rows?.map((row) => (
                  <tr key={rowKey(row)} className="border-t border-border hover:bg-surface-muted">
                    {columns.map((c) => (
                      <td key={c.key} className={cn("h-13 px-4 py-3 align-middle text-fg sm:px-6", c.hideOnMobile && "hidden md:table-cell", c.className)}>{c.cell(row)}</td>
                    ))}
                  </tr>
                ))}
          </tbody>
        </table>
      </div>
      {!loading && rows && rows.length === 0 && <EmptyState title={empty?.title ?? "No results"} description={empty?.description} action={empty?.action} />}
      {page && page.totalPages > 1 && (
        <div className="border-t border-border px-4 py-3">
          <Pagination {...page} />
        </div>
      )}
    </div>
  );
}
