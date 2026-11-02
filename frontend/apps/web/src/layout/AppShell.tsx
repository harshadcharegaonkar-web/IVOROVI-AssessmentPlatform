import { Avatar, Breadcrumbs, cn, focusRing, LinkProvider, Menu, MenuItem, MenuLabel, MenuSeparator, SideSheet, type Crumb } from "@ivorovi/ui";
import { ChevronDown, LogOut, Menu as MenuIcon, Search, UserRound } from "lucide-react";
import { lazy, Suspense, useEffect, useState, type AnchorHTMLAttributes } from "react";
import { useAuth } from "react-oidc-context";
import { Link as RouterLink, Outlet, useLocation, useMatches, useNavigate } from "react-router";
import { readPreference, writePreference } from "../lib/preferences";
import { useMediaQuery } from "../lib/useMediaQuery";
import { InstitutionContextProvider, useInstitutionContext } from "../session/InstitutionContext";
import { useSession } from "../session/SessionProvider";
import { NotificationCenter } from "./NotificationCenter";
import { Brand, SidebarNav } from "./Sidebar";

// Loaded on first use (keeps the start-up bundle small).
const SearchDialog = lazy(() => import("./SearchDialog").then((m) => ({ default: m.SearchDialog })));

export interface RouteHandle { crumb?: string }

const renderLink = ({ to, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { to: string }) => <RouterLink to={to} {...rest} />;

function useCrumbs(): Crumb[] {
  const matches = useMatches();
  const crumbs = matches
    .filter((m) => (m.handle as RouteHandle | undefined)?.crumb)
    .map((m) => ({ label: (m.handle as RouteHandle).crumb!, to: m.pathname }));
  return crumbs.length && crumbs[0]!.label !== "Dashboard" ? [{ label: "Dashboard", to: "/" }, ...crumbs] : crumbs;
}

/**
 * Application shell (design system §3):
 *  ≥1280 px   navy sidebar (256 px, collapsible to a 72 px rail) + white top bar
 *  768–1279   navy top bar + navy icon rail
 *  <768       navy top bar + navigation drawer
 */
export function AppShell() {
  const isDesktop = useMediaQuery("(min-width: 1280px)");
  const isTablet = useMediaQuery("(min-width: 768px)");
  const [prefCollapsed, setPrefCollapsed] = useState(() => readPreference("sidebarCollapsed") === "true");
  const [drawer, setDrawer] = useState(false);
  const location = useLocation();
  const [lastPath, setLastPath] = useState(location.pathname);
  if (lastPath !== location.pathname) {
    setLastPath(location.pathname);
    setDrawer(false);
  }
  const collapsed = isDesktop ? prefCollapsed : true;
  const toggle = () => setPrefCollapsed((c) => { writePreference("sidebarCollapsed", String(!c)); return !c; });

  return (
    <LinkProvider render={renderLink}>
      <InstitutionContextProvider>
        <div className="flex min-h-screen bg-bg">
          <a href="#main" className="sr-only z-50 rounded bg-surface px-3 py-2 focus:not-sr-only focus:fixed focus:left-2 focus:top-2">
            Skip to content
          </a>
          {isTablet && (
            <aside data-testid="sidebar" className={cn("sticky top-0 h-screen shrink-0 transition-[width] duration-150", collapsed ? "w-[72px]" : "w-64")}>
              <SidebarNav collapsed={collapsed} {...(isDesktop ? { onToggleCollapsed: toggle } : {})} />
            </aside>
          )}
          {!isTablet && (
            <SideSheet open={drawer} onOpenChange={setDrawer} title="Navigation" className="bg-sidebar">
              <div id="mobile-nav" className="h-full"><SidebarNav collapsed={false} /></div>
            </SideSheet>
          )}
          <div className="flex min-w-0 flex-1 flex-col">
            <TopBar navy={!isDesktop} showBrand={!isTablet} onOpenMenu={() => setDrawer(true)} />
            <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[1440px] flex-1 px-4 py-6 md:px-6 xl:px-8 xl:py-8">
              <Outlet />
            </main>
          </div>
        </div>
      </InstitutionContextProvider>
    </LinkProvider>
  );
}

function TopBar({ navy, showBrand, onOpenMenu }: { navy: boolean; showBrand: boolean; onOpenMenu: () => void }) {
  const { me } = useSession();
  const auth = useAuth();
  const navigate = useNavigate();
  const crumbs = useCrumbs();
  const [search, setSearch] = useState(false);
  const { options, selectedId, select } = useInstitutionContext();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearch(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const iconBtn = cn("rounded-lg p-2", focusRing, navy ? "text-sidebar-text hover:bg-sidebar-raised hover:text-white" : "text-muted hover:bg-surface-muted hover:text-fg");
  const branding = (me.tenant?.branding ?? {}) as Record<string, unknown>;
  const orgName = typeof branding["displayName"] === "string" ? branding["displayName"] : me.tenant?.name;

  return (
    <header className={cn("sticky top-0 z-30 flex h-16 items-center gap-2 px-3 md:px-6 xl:px-8",
      navy ? "bg-sidebar text-white" : "border-b border-border bg-surface text-fg")}>
      {showBrand && (
        <button type="button" aria-label="Open navigation" aria-controls="mobile-nav" onClick={onOpenMenu} className={iconBtn}>
          <MenuIcon aria-hidden className="h-5 w-5" />
        </button>
      )}
      {showBrand ? <Brand collapsed={false} compact /> : navy ? (
        <span className="truncate text-sm font-semibold text-white" data-testid="tenant-name">{orgName}</span>
      ) : (
        <Breadcrumbs items={crumbs} className="min-w-0" />
      )}
      {!navy && <span className="sr-only" data-testid="tenant-name">{orgName}</span>}

      <div className="ml-auto flex shrink-0 items-center gap-0.5 sm:gap-2">
        {options.length > 1 && (
          <label className="hidden items-center lg:flex">
            <span className="sr-only">Current institution</span>
            <select data-testid="institution-selector" value={selectedId ?? ""} onChange={(e) => select(e.target.value || null)}
              className={cn("h-9 max-w-56 truncate rounded-lg border px-2 text-sm", focusRing,
                navy ? "border-white/20 bg-sidebar-raised text-white" : "border-border-strong bg-surface text-fg")}>
              <option value="">All institutions</option>
              {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
            </select>
          </label>
        )}
        <button type="button" onClick={() => setSearch(true)} aria-label="Search (Ctrl+K)"
          className={cn(iconBtn, !navy && "md:flex md:w-56 md:items-center md:gap-2 md:border md:border-border-strong md:px-3 md:text-sm")}>
          <Search aria-hidden className="h-5 w-5 md:h-4 md:w-4" />
          {!navy && <span className="hidden flex-1 text-left md:inline">Search…</span>}
          {!navy && <kbd className="hidden rounded border border-border px-1.5 text-[11px] text-muted md:inline">Ctrl K</kbd>}
        </button>
        <NotificationCenter buttonClassName={iconBtn} />
        <Menu trigger={
          <button type="button" aria-label="Account menu" className={cn("flex items-center gap-2 rounded-lg py-1 pl-1 pr-2", focusRing,
            navy ? "hover:bg-sidebar-raised" : "hover:bg-surface-muted")}>
            <Avatar name={me.displayName ?? "?"} size="sm" />
            <span className="hidden text-left text-sm leading-tight lg:block">
              <span className="block font-medium" data-testid="user-name">{me.displayName}</span>
              <span className={cn("block text-xs", navy ? "text-sidebar-text" : "text-muted")}>{orgName}</span>
            </span>
            <ChevronDown aria-hidden className="hidden h-4 w-4 lg:block" />
          </button>
        }>
          <MenuLabel>
            <span className="block font-semibold">{me.displayName}</span>
            <span className="block text-xs text-muted">{me.email}</span>
            <span className="block text-xs text-muted">{orgName}</span>
          </MenuLabel>
          <MenuSeparator />
          <MenuItem onSelect={() => void navigate("/me")}><UserRound aria-hidden className="h-4 w-4" /> My Access</MenuItem>
          <MenuItem tone="danger" onSelect={() => void auth.signoutRedirect()}><LogOut aria-hidden className="h-4 w-4" /> Sign out</MenuItem>
        </Menu>
      </div>
      {search && <Suspense fallback={null}><SearchDialog open={search} onOpenChange={setSearch} /></Suspense>}
    </header>
  );
}
