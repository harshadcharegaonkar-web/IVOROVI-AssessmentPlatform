/**
 * Browser boundary of the exam application (C-4). Same values as deploy/default.conf.template (nginx); the header test
 * (e2e/headers.spec.ts) checks the served build against them.
 *  - no inline scripts or styles; connect only to this origin (delivery API proxied here) and the candidate realm;
 *  - never framed; no referrer; only camera (system check / later proctoring) and fullscreen are allowed;
 *  - cross-origin isolated (COOP/COEP), so no other window or embedded resource shares the process.
 */
export function examCsp(oidcAuthority: string): string {
  return [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self' ${oidcAuthority}/`,
    `form-action 'self' ${oidcAuthority}/`,
    "manifest-src 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "object-src 'none'",
  ].join("; ");
}

export function examHeaders(oidcAuthority: string): Record<string, string> {
  return {
    "Content-Security-Policy": examCsp(oidcAuthority),
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(self), fullscreen=(self), microphone=(), geolocation=(), payment=(), usb=(), display-capture=()",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp",
    "Cross-Origin-Resource-Policy": "same-origin",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  };
}
