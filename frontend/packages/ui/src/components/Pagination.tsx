import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./Button";

/** Zero-based page navigation. */
export function Pagination({ page, totalPages, totalElements, onPageChange }: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPageChange: (page: number) => void;
}) {
  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted">
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)} · {totalElements} total
      </span>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" onClick={() => onPageChange(page - 1)} disabled={page <= 0} aria-label="Previous page">
          <ChevronLeft aria-hidden className="h-4 w-4" /> Previous
        </Button>
        <Button variant="secondary" size="sm" onClick={() => onPageChange(page + 1)} disabled={page + 1 >= totalPages} aria-label="Next page">
          Next <ChevronRight aria-hidden className="h-4 w-4" />
        </Button>
      </div>
    </nav>
  );
}
