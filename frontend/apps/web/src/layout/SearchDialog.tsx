import { unwrap } from "@ivorovi/api-client";
import { cn, Dialog, Spinner } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { Building2, CornerDownLeft, Search } from "lucide-react";
import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import { useNavigate } from "react-router";
import { useApi } from "../api/ApiProvider";
import { useSession } from "../session/SessionProvider";
import { visibleNav } from "./navigation";

interface Result { id: string; group: string; label: string; detail?: string | undefined; to: string; icon: typeof Building2 }

/**
 * Global search (Ctrl/⌘ K): permitted pages plus records the caller can read. Sources grow with each increment
 * (org units, users, students); results always come from permission-checked API endpoints.
 */
export function SearchDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const api = useApi();
  const navigate = useNavigate();
  const { access } = useSession();
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [active, setActive] = useState(0);

  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(q.trim()), 250);
    return () => window.clearTimeout(t);
  }, [q]);

  const institutions = useQuery({
    queryKey: ["search", "institutions", debounced],
    enabled: open && debounced.length >= 2 && access.hasAnywhere("institution.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { q: debounced, size: 5 } } })),
  });

  const results = useMemo<Result[]>(() => {
    const term = q.trim().toLowerCase();
    const pages = visibleNav(access)
      .filter((n) => !term || n.label.toLowerCase().includes(term))
      .map((n) => ({ id: "page:" + n.to, group: "Pages", label: n.label, to: n.to, icon: n.icon }));
    const insts = (debounced.length >= 2 ? institutions.data?.items ?? [] : []).map((i) => ({
      id: "inst:" + i.id, group: "Institutions", label: i.name ?? "", detail: i.code, to: `/institutions/${i.id}`, icon: Building2,
    }));
    return [...pages, ...insts];
  }, [q, debounced, access, institutions.data]);

  const go = (r: Result | undefined) => {
    if (!r) return;
    onOpenChange(false);
    setQ("");
    void navigate(r.to);
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, results.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === "Enter") { e.preventDefault(); go(results[active]); }
  };

  let lastGroup = "";
  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) setQ(""); }} title="Search">
      <div className="flex items-center gap-3 border-b border-border px-4">
        <Search aria-hidden className="h-5 w-5 text-muted" />
        <input
          autoFocus
          role="combobox"
          aria-expanded
          aria-controls="search-results"
          aria-activedescendant={results[active] ? `sr-${results[active].id}` : undefined}
          aria-label="Search pages and records"
          placeholder="Search pages and institutions…"
          value={q}
          onChange={(e) => { setQ(e.target.value); setActive(0); }}
          onKeyDown={onKey}
          className="h-14 w-full bg-transparent text-base text-fg outline-none placeholder:text-muted"
        />
        {institutions.isFetching && <Spinner size="sm" label="Searching" />}
      </div>
      <ul id="search-results" role="listbox" aria-label="Search results" className="max-h-[50vh] overflow-y-auto p-2">
        {results.length === 0 && <li className="px-3 py-8 text-center text-sm text-muted">No matches for “{q}”.</li>}
        {results.map((r, i) => {
          const header = r.group !== lastGroup ? r.group : null;
          lastGroup = r.group;
          const Icon = r.icon;
          return (
            <li key={r.id} role="presentation">
              {header && <p className="px-3 pb-1 pt-3 text-[11px] font-semibold uppercase tracking-wider text-muted">{header}</p>}
              <div id={`sr-${r.id}`} role="option" aria-selected={i === active} onMouseEnter={() => setActive(i)} onClick={() => go(r)}
                className={cn("flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm", i === active ? "bg-primary-soft text-fg" : "text-fg")}>
                <Icon aria-hidden className="h-4 w-4 text-muted" />
                <span className="flex-1 truncate font-medium">{r.label}</span>
                {r.detail && <code className="text-xs text-muted">{r.detail}</code>}
                {i === active && <CornerDownLeft aria-hidden className="h-4 w-4 text-muted" />}
              </div>
            </li>
          );
        })}
      </ul>
    </Dialog>
  );
}
