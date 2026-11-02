import type { ReactNode } from "react";

/** Search and filter controls above a table, inside the table card. */
export function FilterBar({ children, trailing }: { children: ReactNode; trailing?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end gap-3 border-b border-border px-4 py-4 sm:px-6">
      {children}
      {trailing && <div className="ml-auto flex items-center gap-2">{trailing}</div>}
    </div>
  );
}
