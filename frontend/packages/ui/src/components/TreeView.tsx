import { ChevronRight } from "lucide-react";
import { useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { cn } from "../lib/cn";

export interface TreeItem {
  id: string;
  parentId: string | null | undefined;
  label: string;
}

interface Node<T extends TreeItem> { item: T; children: Node<T>[]; depth: number }

/**
 * Accessible tree (WAI-ARIA treeview): ↑/↓ move, → expands or enters, ← collapses or goes to the parent,
 * Home/End jump, Enter/Space select. Items whose parent is not in the list become roots (scoped views).
 */
export function TreeView<T extends TreeItem>({ items, label, selectedId, onSelect, renderItem, defaultExpandedDepth = 1 }: {
  items: T[];
  label: string;
  selectedId?: string | null | undefined;
  onSelect: (item: T) => void;
  renderItem?: (item: T) => ReactNode;
  defaultExpandedDepth?: number;
}) {
  const roots = useMemo(() => {
    const byId = new Map(items.map((i) => [i.id, { item: i, children: [] as Node<T>[], depth: 0 }]));
    const top: Node<T>[] = [];
    for (const n of byId.values()) {
      const parent = n.item.parentId ? byId.get(n.item.parentId) : undefined;
      if (parent) parent.children.push(n); else top.push(n);
    }
    const setDepth = (n: Node<T>, d: number) => { n.depth = d; n.children.forEach((c) => setDepth(c, d + 1)); };
    top.forEach((r) => setDepth(r, 0));
    return top;
  }, [items]);

  const [expanded, setExpanded] = useState<Set<string>>(() => {
    const s = new Set<string>();
    const walk = (n: Node<T>) => { if (n.depth < defaultExpandedDepth) s.add(n.item.id); n.children.forEach(walk); };
    roots.forEach(walk);
    return s;
  });
  const [focusId, setFocusId] = useState<string | null>(selectedId ?? roots[0]?.item.id ?? null);
  const refs = useRef(new Map<string, HTMLLIElement>());

  const visible: Node<T>[] = [];
  const collect = (n: Node<T>) => { visible.push(n); if (expanded.has(n.item.id)) n.children.forEach(collect); };
  roots.forEach(collect);
  const activeId = focusId && visible.some((v) => v.item.id === focusId) ? focusId : visible[0]?.item.id ?? null;

  const focus = (id: string | undefined) => {
    if (!id) return;
    setFocusId(id);
    refs.current.get(id)?.focus();
  };
  const toggle = (id: string, open?: boolean) =>
    setExpanded((s) => { const n = new Set(s); if (open ?? !n.has(id)) n.add(id); else n.delete(id); return n; });

  const onKeyDown = (e: KeyboardEvent<HTMLLIElement>, node: Node<T>) => {
    const i = visible.findIndex((v) => v.item.id === node.item.id);
    const isOpen = expanded.has(node.item.id);
    switch (e.key) {
      case "ArrowDown": e.preventDefault(); focus(visible[i + 1]?.item.id); break;
      case "ArrowUp": e.preventDefault(); focus(visible[i - 1]?.item.id); break;
      case "Home": e.preventDefault(); focus(visible[0]?.item.id); break;
      case "End": e.preventDefault(); focus(visible[visible.length - 1]?.item.id); break;
      case "ArrowRight":
        e.preventDefault();
        if (node.children.length && !isOpen) toggle(node.item.id, true);
        else if (isOpen) focus(node.children[0]?.item.id);
        break;
      case "ArrowLeft":
        e.preventDefault();
        if (isOpen) toggle(node.item.id, false);
        else if (node.item.parentId) focus(node.item.parentId);
        break;
      case "Enter": case " ": e.preventDefault(); onSelect(node.item); break;
    }
  };

  const render = (n: Node<T>): ReactNode => {
    const isOpen = expanded.has(n.item.id);
    const selected = n.item.id === selectedId;
    return (
      <li key={n.item.id} role="treeitem" aria-level={n.depth + 1} aria-selected={selected}
        aria-expanded={n.children.length ? isOpen : undefined} tabIndex={n.item.id === activeId ? 0 : -1}
        ref={(el) => { if (el) refs.current.set(n.item.id, el); else refs.current.delete(n.item.id); }}
        onKeyDown={(e) => { e.stopPropagation(); onKeyDown(e, n); }}
        onFocus={(e) => { if (e.target === e.currentTarget) setFocusId(n.item.id); }}
        className="outline-none focus-visible:[&>div]:outline-2 focus-visible:[&>div]:outline-focus">
        <div className={cn("flex min-h-10 cursor-pointer items-center gap-1 rounded-lg pr-2 text-sm",
          selected ? "bg-primary-soft font-semibold text-fg" : "text-fg hover:bg-surface-muted")}
          style={{ paddingLeft: `${n.depth * 20 + 4}px` }}
          onClick={() => { setFocusId(n.item.id); onSelect(n.item); }}>
          {n.children.length ? (
            <span role="presentation" onClick={(e) => { e.stopPropagation(); toggle(n.item.id); }}
              className="flex h-7 w-7 shrink-0 items-center justify-center rounded text-muted hover:bg-surface-muted">
              <ChevronRight aria-hidden className={cn("h-4 w-4 transition-transform", isOpen && "rotate-90")} />
            </span>
          ) : <span aria-hidden className="w-7 shrink-0" />}
          <span className="min-w-0 flex-1 truncate">{renderItem ? renderItem(n.item) : n.item.label}</span>
        </div>
        {n.children.length > 0 && isOpen && <ul role="group">{n.children.map(render)}</ul>}
      </li>
    );
  };

  return <ul role="tree" aria-label={label} className="space-y-0.5">{roots.map(render)}</ul>;
}
