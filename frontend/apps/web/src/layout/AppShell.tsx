import { cn, focusRing } from "@ivorovi/ui";
import { GraduationCap, LogOut, Menu, X } from "lucide-react";
import { useState } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, Outlet, useLocation } from "react-router";
import { useSession } from "../session/SessionProvider";
import { visibleNav } from "./navigation";

export function AppShell() {
  const { me, access } = useSession();
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const [lastPath, setLastPath] = useState(location.pathname);
  if (lastPath !== location.pathname) {
    // Close the mobile drawer after navigation.
    setLastPath(location.pathname);
    setOpen(false);
  }
  const items = visibleNav(access);
  const branding = (me.tenant?.branding ?? {}) as Record<string, unknown>;
  const orgName = typeof branding["displayName"] === "string" ? branding["displayName"] : me.tenant?.name;

  const nav = (
    <nav aria-label="Main" className="flex flex-col gap-1 p-3">
      {items.map(({ to, label, icon: Icon }) => (
        <NavLink
          key={to}
          to={to}
          end={to === "/"}
          className={({ isActive }) => cn(
            "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium",
            focusRing,
            isActive ? "bg-primary text-primary-contrast" : "text-muted hover:bg-surface-muted hover:text-fg",
          )}
        >
          <Icon aria-hidden className="h-4 w-4" />
          {label}
        </NavLink>
      ))}
    </nav>
  );

  return (
    <div className="min-h-screen">
      <a href="#main" className="sr-only z-50 rounded bg-surface px-3 py-2 focus:not-sr-only focus:fixed focus:left-2 focus:top-2">
        Skip to content
      </a>
      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-border bg-surface px-4">
        <button type="button" className={cn("rounded-md p-2 md:hidden", focusRing)} aria-label={open ? "Close navigation" : "Open navigation"}
          aria-expanded={open} aria-controls="mobile-nav" onClick={() => setOpen((o) => !o)}>
          {open ? <X aria-hidden className="h-5 w-5" /> : <Menu aria-hidden className="h-5 w-5" />}
        </button>
        <div className="flex min-w-0 items-center gap-2">
          <span aria-hidden className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary text-primary-contrast">
            <GraduationCap className="h-5 w-5" />
          </span>
          <span className="truncate font-semibold" data-testid="tenant-name">{orgName}</span>
        </div>
        <div className="ml-auto flex items-center gap-3">
          <span className="hidden text-right text-sm sm:block">
            <span className="block font-medium leading-tight" data-testid="user-name">{me.displayName}</span>
            <span className="block text-xs text-muted">{me.email}</span>
          </span>
          <button type="button" onClick={() => void auth.signoutRedirect()}
            className={cn("inline-flex items-center gap-2 rounded-md border border-border px-3 py-1.5 text-sm hover:bg-surface-muted", focusRing)}>
            <LogOut aria-hidden className="h-4 w-4" /> Sign out
          </button>
        </div>
      </header>
      <div className="flex">
        <aside className="sticky top-14 hidden h-[calc(100vh-3.5rem)] w-60 shrink-0 border-r border-border bg-surface md:block">{nav}</aside>
        {open && (
          <div id="mobile-nav" className="fixed inset-0 top-14 z-20 bg-surface md:hidden">{nav}</div>
        )}
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 py-6 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
