import { createContext, useContext, type AnchorHTMLAttributes, type ReactNode } from "react";
import { buttonClasses } from "./Button";

/**
 * Router-agnostic link: the app provides a render function for its router's link once, so UI components can
 * link without depending on a routing library. Falls back to a plain anchor.
 */
export type LinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & { to: string };
type RenderLink = (props: LinkProps) => ReactNode;
const LinkContext = createContext<RenderLink | null>(null);

export function LinkProvider({ render, children }: { render: RenderLink; children: ReactNode }) {
  return <LinkContext.Provider value={render}>{children}</LinkContext.Provider>;
}

export function Link({ to, ...rest }: LinkProps) {
  const render = useContext(LinkContext);
  return render ? render({ to, ...rest }) : <a href={to} {...rest} />;
}

/** Navigation that looks like a button (never nest a <button> inside a link). */
export function ButtonLink({ to, variant = "secondary", size = "md", className, ...rest }: LinkProps & {
  variant?: "primary" | "secondary" | "ghost" | "danger"; size?: "sm" | "md";
}) {
  return <Link to={to} className={buttonClasses(variant, size, className)} {...rest} />;
}
