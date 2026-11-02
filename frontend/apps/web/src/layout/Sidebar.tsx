import { cn, Tooltip } from "@ivorovi/ui";
import { ChevronDown, GraduationCap, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useState } from "react";
import { NavLink } from "react-router";
import { useSession } from "../session/SessionProvider";
import { useOrgLabels } from "../session/useOrgLabels";
import { FOOTER_ITEMS, type NavItem, visibleGroups } from "./navigation";

const navFocus = "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white";

export function Brand({ collapsed, compact }: { collapsed: boolean; compact?: boolean }) {
  return (
    <div className={cn("flex h-16 min-w-0 shrink items-center gap-3", collapsed ? "justify-center px-0" : compact ? "px-1" : "px-5")}>
      <span aria-hidden className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white text-sidebar">
        <GraduationCap className="h-5 w-5" strokeWidth={2} />
      </span>
      {!collapsed && (
        <span className="min-w-0 leading-tight">
          <span className="block text-[15px] font-bold tracking-wide text-white">IVOROVI</span>
          <span className={cn("block truncate text-xs text-sidebar-text", compact && "hidden min-[400px]:block")}>Assessment Platform</span>
        </span>
      )}
    </div>
  );
}

function Item({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const labels = useOrgLabels();
  const Icon = item.icon;
  const text = item.orgType ? labels.plural(item.orgType) : item.label;
  return (
    <Tooltip content={text} disabled={!collapsed}>
      <NavLink
        to={item.to}
        end={item.to === "/" || item.to === "/organization"}
        aria-label={collapsed ? text : undefined}
        className={({ isActive }) => cn(
          "group relative flex h-10 items-center gap-3 rounded-lg text-sm font-medium transition-colors",
          collapsed ? "justify-center px-0" : "px-3",
          navFocus,
          isActive ? "bg-sidebar-active text-white" : "text-sidebar-text hover:bg-sidebar-raised hover:text-white",
        )}
      >
        {({ isActive }) => (
          <>
            {isActive && <span aria-hidden className="absolute inset-y-2 left-0 w-[3px] rounded-r bg-primary" />}
            <Icon aria-hidden className="h-5 w-5 shrink-0" strokeWidth={1.75} />
            {!collapsed && <span className="truncate">{text}</span>}
          </>
        )}
      </NavLink>
    </Tooltip>
  );
}

/** Navy navigation: grouped, permission-filtered, collapsible groups; icon rail when collapsed. */
export function SidebarNav({ collapsed, onToggleCollapsed }: { collapsed: boolean; onToggleCollapsed?: () => void }) {
  const { me, access } = useSession();
  const groups = visibleGroups(access);
  const footer = FOOTER_ITEMS.filter((i) => i.visible(access));
  const [closed, setClosed] = useState<Record<string, boolean>>({});
  const branding = (me.tenant?.branding ?? {}) as Record<string, unknown>;
  const orgName = typeof branding["displayName"] === "string" ? branding["displayName"] : me.tenant?.name;

  return (
    <div className="flex h-full flex-col bg-sidebar">
      <Brand collapsed={collapsed} />
      {!collapsed && (
        <div className="mx-3 mb-2 rounded-lg bg-sidebar-raised px-3 py-2.5">
          <p className="truncate text-sm font-semibold text-white" data-testid="sidebar-org">{orgName}</p>
          <p className="truncate text-xs text-sidebar-text">{(me.roles ?? []).map((r) => r.replace(/_/g, " ").toLowerCase()).join(", ") || "No role assigned"}</p>
        </div>
      )}
      <nav aria-label="Main" className="flex-1 overflow-y-auto px-3 py-2">
        {groups.map((g) => {
          const open = collapsed || !closed[g.id];
          return (
            <div key={g.id} className="mb-3">
              {g.label && !collapsed && (
                <button type="button" aria-expanded={open} onClick={() => setClosed((c) => ({ ...c, [g.id]: !c[g.id] }))}
                  className={cn("flex w-full items-center justify-between rounded px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-sidebar-muted hover:text-white", navFocus)}>
                  {g.label}
                  <ChevronDown aria-hidden className={cn("h-3.5 w-3.5 transition-transform", !open && "-rotate-90")} />
                </button>
              )}
              {open && <div className="space-y-1">{g.items.map((i) => <Item key={i.to} item={i} collapsed={collapsed} />)}</div>}
            </div>
          );
        })}
      </nav>
      <div className="space-y-1 border-t border-white/10 px-3 py-3">
        {footer.map((i) => <Item key={i.to} item={i} collapsed={collapsed} />)}
        {onToggleCollapsed && (
          <Tooltip content={collapsed ? "Expand sidebar" : "Collapse sidebar"} disabled={!collapsed}>
            <button type="button" onClick={onToggleCollapsed} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
              className={cn("flex h-10 w-full items-center gap-3 rounded-lg text-sm font-medium text-sidebar-text hover:bg-sidebar-raised hover:text-white",
                collapsed ? "justify-center" : "px-3", navFocus)}>
              {collapsed ? <PanelLeftOpen aria-hidden className="h-5 w-5" /> : <PanelLeftClose aria-hidden className="h-5 w-5" />}
              {!collapsed && "Collapse"}
            </button>
          </Tooltip>
        )}
      </div>
    </div>
  );
}
