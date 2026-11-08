import { texProblem } from "./format";

/**
 * Mathematics rendering (ADR-0010, approved decision L-2.1-1): KaTeX 0.16.47, bundled and served by this application (no
 * CDN), loaded only when a page shows a formula.
 *
 * Safety layers, each sufficient on its own for the threats it covers:
 *  1. the server and {@link texProblem} refuse link, image, HTML, macro-definition and file commands, long formulas and
 *     control characters;
 *  2. KaTeX runs with MathML output only (no HTML output), {@code trust: false} (no \href, \url, \includegraphics,
 *     \htmlClass/\htmlId/\htmlStyle/\htmlData), a fresh macro table per formula, bounded size and expansion;
 *  3. {@code katex.render} builds DOM nodes with createElement/textContent — no HTML string is parsed;
 *  4. {@link sanitizeMathTree} then keeps only an allowlist of MathML elements and attributes (no event handlers, links,
 *     styles or foreign elements), so even a renderer defect cannot introduce active content.
 * If KaTeX cannot be loaded or rejects a formula, the formula's source is shown as text (see {@link MathResult}).
 */

export type MathResult = { ok: true } | { ok: false; reason: "rejected" | "unavailable" | "invalid"; message: string };

type Katex = typeof import("katex").default;
let loading: Promise<Katex> | null = null;

/** Lazily loads the bundled KaTeX (one chunk, cached). A failed load can be retried. */
export function loadKatex(): Promise<Katex> {
  loading ??= import("katex").then((m) => m.default).catch((e: unknown) => { loading = null; throw e; });
  return loading;
}

const OPTIONS = {
  output: "mathml",
  throwOnError: true,
  trust: false,
  strict: "ignore",
  maxSize: 20,
  maxExpand: 200,
  globalGroup: false,
} as const;

const MATHML = new Set(["math", "semantics", "annotation", "mrow", "mi", "mn", "mo", "ms", "mtext", "mspace", "msub", "msup", "msubsup", "mfrac", "msqrt",
  "mroot", "mstyle", "mtable", "mtr", "mtd", "mlabeledtr", "mover", "munder", "munderover", "mpadded", "mphantom", "menclose", "merror",
  "mmultiscripts", "mprescripts", "none"]);
const ATTRS = new Set(["xmlns", "display", "encoding", "mathvariant", "stretchy", "fence", "separator", "lspace", "rspace", "minsize", "maxsize",
  "symmetric", "largeop", "movablelimits", "accent", "accentunder", "form", "width", "height", "depth", "voffset", "columnalign", "rowalign",
  "columnspacing", "rowspacing", "columnlines", "rowlines", "frame", "framespacing", "notation", "scriptlevel", "displaystyle", "linethickness",
  "mathcolor", "mathbackground", "align"]);
const SAFE_VALUE = /^[#A-Za-z0-9 .,%()+-]*$/;
/** Attributes whose only legitimate value is fixed (KaTeX's namespace and TeX annotation). */
const EXACT: Record<string, string> = { xmlns: "http://www.w3.org/1998/Math/MathML", encoding: "application/x-tex" };

function attributeAllowed(name: string, value: string): boolean {
  if (name in EXACT) return value === EXACT[name];
  return ATTRS.has(name) && SAFE_VALUE.test(value);
}

/**
 * Keeps only allowlisted MathML (plus KaTeX's wrapper span). Removes any other element with its content, every attribute
 * not on the list, and attribute values with characters outside a plain set (so no {@code url(...)} or script schemes).
 * Returns the number of removed elements/attributes (0 for KaTeX's normal output).
 */
export function sanitizeMathTree(root: Element): number {
  let removed = 0;
  const walk = (el: Element) => {
    for (const child of Array.from(el.children)) {
      const name = child.localName.toLowerCase();
      const wrapper = name === "span" && child.namespaceURI === "http://www.w3.org/1999/xhtml" && child.getAttribute("class") === "katex" && el === root;
      if (!wrapper && (!MATHML.has(name) || child.namespaceURI !== "http://www.w3.org/1998/Math/MathML")) {
        child.remove();
        removed++;
        continue;
      }
      for (const a of Array.from(child.attributes)) {
        const keep = wrapper ? a.name === "class" : attributeAllowed(a.name.toLowerCase(), a.value);
        if (!keep) { child.removeAttribute(a.name); removed++; }
      }
      walk(child);
    }
  };
  walk(root);
  return removed;
}

/** Renders {@code tex} into {@code target} (replacing its content) or leaves the source text in it, explaining why. */
export async function renderMath(tex: string, target: HTMLElement, display: boolean): Promise<MathResult> {
  const showSource = () => { target.textContent = tex; };
  const problem = texProblem(tex);
  if (problem) { showSource(); return { ok: false, reason: "rejected", message: problem }; }
  let katex: Katex;
  try {
    katex = await loadKatex();
  } catch {
    showSource();
    return { ok: false, reason: "unavailable", message: "Formula rendering is unavailable; showing the formula source." };
  }
  const scratch = target.ownerDocument.createElement("span");
  try {
    katex.render(tex, scratch, { ...OPTIONS, displayMode: display, macros: {} });
  } catch (e) {
    showSource();
    return { ok: false, reason: "invalid", message: e instanceof Error ? e.message.slice(0, 200) : "invalid formula" };
  }
  sanitizeMathTree(scratch);
  target.replaceChildren(...Array.from(scratch.childNodes));
  return { ok: true };
}
