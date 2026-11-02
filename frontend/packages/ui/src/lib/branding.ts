const HEX = /^#[0-9a-fA-F]{6}$/;

/** Relative luminance (WCAG) of a #rrggbb colour. */
function luminance(hex: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r, g, b] = channels.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)) as [
    number,
    number,
    number,
  ];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * Applies a tenant brand colour. Untrusted input: only strict #rrggbb is accepted; the contrast colour is
 * chosen automatically so text on the brand colour stays readable.
 */
export function applyBrandColor(color: unknown, root: HTMLElement = document.documentElement): boolean {
  if (typeof color !== "string" || !HEX.test(color)) return false;
  root.style.setProperty("--brand-primary", color);
  root.style.setProperty("--brand-primary-contrast", luminance(color) > 0.45 ? "#111827" : "#ffffff");
  return true;
}
