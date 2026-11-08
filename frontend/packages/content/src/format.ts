/**
 * Question content format {@code ivorovi.content.v1} (ADR-0010) — the client side of the server's ContentFormat: a
 * structured document of paragraphs (text and inline-math runs), display math, images and lists. Never HTML: renderers
 * build DOM nodes from this structure and text always becomes text nodes.
 */
export const CONTENT_FORMAT = "ivorovi.content.v1";

export type Mark = "bold" | "italic" | "sub" | "sup";
export type Run = { text: string; marks?: Mark[] } | { math: string };
export type Block =
  | { type: "paragraph"; runs: Run[] }
  | { type: "math"; tex: string }
  | { type: "image"; asset: string; alt?: string; decorative?: boolean }
  | { type: "list"; ordered?: boolean; items: { runs: Run[] }[] };
export interface ContentDoc { format: typeof CONTENT_FORMAT; blocks: Block[] }

/** Maximum formula length, same as the server (TexGuard.MAX_LENGTH). */
export const MAX_TEX = 1000;

/** Commands refused by the server (TexGuard) and checked again here before any rendering. */
const DENIED = /\\(href|url|includegraphics|html[A-Za-z]*|def|gdef|edef|xdef|let|futurelet|newcommand|renewcommand|providecommand|DeclareMathOperator|newenvironment|renewenvironment|input|include|write|immediate|openin|openout|read|catcode|csname|endcsname|expandafter|char|mathchar|special|verb)(?![A-Za-z])/;

/** Null when the formula is acceptable; otherwise the reason (mirrors the server rules). */
export function texProblem(tex: string): string | null {
  if (!tex.trim()) return "empty formula";
  if (tex.length > MAX_TEX) return `formula longer than ${MAX_TEX} characters`;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(tex)) return "control character in formula";
  if (DENIED.test(tex)) return "formula uses a command that is not allowed";
  let depth = 0;
  for (let i = 0; i < tex.length; i++) {
    const ch = tex[i];
    if (ch === "\\") { i++; continue; }
    if (ch === "{") depth++;
    if (ch === "}" && --depth < 0) return "unbalanced braces in formula";
  }
  return depth === 0 ? null : "unbalanced braces in formula";
}

/**
 * Source syntax of the editor and imports (same as the server's ContentFormat.fromSource): paragraphs separated by blank
 * lines, {@code $…$} inline math, {@code $$…$$} display math on its own, {@code \$} for a dollar sign.
 */
export function fromSource(source: string): ContentDoc {
  const blocks: Block[] = [];
  for (const raw of source.replace(/\r\n?/g, "\n").split(/\n\s*\n/)) {
    const para = raw.trim();
    if (!para) continue;
    if (para.startsWith("$$") && para.endsWith("$$") && para.length > 4 && !para.slice(2, -2).includes("$$")) {
      blocks.push({ type: "math", tex: para.slice(2, -2).trim() });
      continue;
    }
    const runs: Run[] = [];
    let text = "";
    let i = 0;
    while (i < para.length) {
      const ch = para[i];
      if (ch === "\\" && para[i + 1] === "$") { text += "$"; i += 2; continue; }
      if (ch === "$") {
        const end = findClosingDollar(para, i + 1);
        if (end > i + 1) {
          if (text) { runs.push({ text }); text = ""; }
          runs.push({ math: para.slice(i + 1, end) });
          i = end + 1;
          continue;
        }
      }
      text += ch;
      i++;
    }
    if (text) runs.push({ text });
    if (runs.length) blocks.push({ type: "paragraph", runs });
  }
  return { format: CONTENT_FORMAT, blocks: blocks.length ? blocks : [{ type: "paragraph", runs: [{ text: "" }] }] };
}

function findClosingDollar(s: string, from: number): number {
  for (let j = from; j < s.length; j++) {
    if (s[j] === "\\") { j++; continue; }
    if (s[j] === "$") return j;
  }
  return -1;
}

/** Inverse of {@link fromSource} for text and math (images are kept separately by the editor). */
export function toSource(doc: ContentDoc | undefined | null): { source: string; images: Extract<Block, { type: "image" }>[] } {
  const parts: string[] = [];
  const images: Extract<Block, { type: "image" }>[] = [];
  const runs = (rs: Run[]) => rs.map((r) => ("math" in r ? `$${r.math}$` : r.text.replace(/\$/g, "\\$"))).join("");
  for (const b of doc?.blocks ?? []) {
    if (b.type === "paragraph") parts.push(runs(b.runs));
    else if (b.type === "math") parts.push(`$$${b.tex}$$`);
    else if (b.type === "list") parts.push(b.items.map((it) => runs(it.runs)).join("\n"));
    else images.push(b);
  }
  return { source: parts.join("\n\n"), images };
}

/** Plain text (formulas as their source) for excerpts and accessible names. */
export function plainText(doc: ContentDoc | undefined | null): string {
  return toSource(doc).source;
}
