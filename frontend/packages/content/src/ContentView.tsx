import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { Block, ContentDoc, Run } from "./format";
import { renderMath, type MathResult } from "./math";

/**
 * A formula. Before KaTeX has rendered (or when it cannot), the TeX source is shown as text with a label, so the content
 * is never lost and screen readers announce it as a formula. Rendered output is native MathML, which browsers expose to
 * assistive technology; the TeX source is kept as the MathML annotation.
 */
export function MathView({ tex, display = false }: { tex: string; display?: boolean }) {
  const ref = useRef<HTMLSpanElement>(null);
  const [result, setResult] = useState<MathResult | null>(null);
  // the source text first (synchronously, so it is never blank), then the rendered MathML replaces it
  useLayoutEffect(() => { if (ref.current) ref.current.textContent = tex; }, [tex]);
  useEffect(() => {
    let live = true;
    const el = ref.current;
    if (!el) return;
    void renderMath(tex, el, display).then((r) => { if (live) setResult(r); });
    return () => { live = false; };
  }, [tex, display]);
  const failed = result && !result.ok;
  return (
    <span className={display ? "my-2 block overflow-x-auto text-center" : "inline-block max-w-full overflow-x-auto align-middle"}
      data-math={failed ? "fallback" : result ? "rendered" : "pending"} title={failed ? result.message : undefined}>
      {/* React owns this wrapper only; the inner span's children are set by renderMath (DOM nodes, never an HTML string). */}
      <span ref={ref} className={failed ? "font-mono text-sm" : undefined} aria-label={failed ? `Formula: ${tex}` : undefined}
        role={failed ? "img" : undefined} />
    </span>
  );
}

function Runs({ runs }: { runs: Run[] }) {
  return (
    <>
      {runs.map((r, i) => {
        if ("math" in r) return <MathView key={i} tex={r.math} />;
        let node: ReactNode = r.text;
        for (const m of r.marks ?? []) {
          node = m === "bold" ? <strong>{node}</strong> : m === "italic" ? <em>{node}</em> : m === "sub" ? <sub>{node}</sub> : <sup>{node}</sup>;
        }
        return <span key={i}>{node}</span>;
      })}
    </>
  );
}

/** Image sources are resolved by the caller (authenticated fetch → data: URL); unresolved images show their description. */
export type ImageSource = (assetId: string) => string | undefined;

function ImageBlock({ block, src }: { block: Extract<Block, { type: "image" }>; src: ImageSource }) {
  const url = src(block.asset);
  const alt = block.decorative ? "" : block.alt ?? "";
  if (!url) return <span className="my-2 block rounded border border-dashed border-border p-3 text-sm text-fg-muted">{block.decorative ? "Image" : `Image: ${alt}`}</span>;
  return <img src={url} alt={alt} className="my-2 block h-auto max-h-96 max-w-full" loading="lazy" decoding="async" referrerPolicy="no-referrer" />;
}

/** Renders question content. Text is always React text (escaped); there is no dangerouslySetInnerHTML anywhere. */
export function ContentView({ doc, imageSrc = () => undefined, className }: { doc: ContentDoc | null | undefined; imageSrc?: ImageSource; className?: string }) {
  if (!doc?.blocks?.length) return null;
  return (
    <div className={className ?? "space-y-2 leading-relaxed"}>
      {doc.blocks.map((b, i) => {
        switch (b.type) {
          case "paragraph": return <p key={i}><Runs runs={b.runs} /></p>;
          case "math": return <MathView key={i} tex={b.tex} display />;
          case "image": return <ImageBlock key={i} block={b} src={imageSrc} />;
          case "list": {
            const items = b.items.map((it, j) => <li key={j}><Runs runs={it.runs} /></li>);
            return b.ordered ? <ol key={i} className="list-decimal pl-6">{items}</ol> : <ul key={i} className="list-disc pl-6">{items}</ul>;
          }
          default: return null;
        }
      })}
    </div>
  );
}
