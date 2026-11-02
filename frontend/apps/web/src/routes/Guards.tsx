import { ErrorState } from "@ivorovi/ui";
import type { ReactNode } from "react";
import type { Access } from "../session/access";
import { useSession } from "../session/SessionProvider";

/** UX guard for routes. The API enforces the same permission; this only avoids a dead-end screen. */
export function RequirePermission({ check, children }: { check: (a: Access) => boolean; children: ReactNode }) {
  const { access } = useSession();
  if (!check(access)) {
    return <ErrorState title="Access denied" message="Your role does not include access to this page. Contact your administrator if you need it." />;
  }
  return <>{children}</>;
}

export function NotFoundPage() {
  return <ErrorState title="Page not found" message="The page you were looking for does not exist." />;
}
