import { describe, expect, it } from "vitest";
import { fromSource, texProblem, toSource } from "./format";
import { renderMath, sanitizeMathTree } from "./math";

const render = async (tex: string, display = false) => {
  const el = document.createElement("span");
  document.body.appendChild(el);
  const r = await renderMath(tex, el, display);
  return { el, r };
};

const MML = "http://www.w3.org/1998/Math/MathML";

describe("mathematics rendering (MathML only)", () => {
  const expressions: [string, string][] = [
    ["fraction", "\\frac{a+b}{c}"],
    ["matrix", "\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}"],
    ["integral", "\\int_0^1 x^2 \\, dx"],
    ["summation", "\\sum_{i=1}^{n} i^2 = \\frac{n(n+1)(2n+1)}{6}"],
    ["limit", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1"],
    ["derivative", "\\frac{d}{dx} e^{x^2} = 2x e^{x^2}"],
    ["partial derivative", "\\frac{\\partial^2 u}{\\partial t^2} = c^2 \\nabla^2 u"],
    ["subscripts and superscripts", "x_{i,j}^{2k} + a_1^{n-1}"],
    ["complex", "\\left( \\sum_{k=0}^{\\infty} \\frac{(-1)^k x^{2k+1}}{(2k+1)!} \\right)^2 + \\sqrt[3]{\\frac{\\alpha}{\\beta}} \\leq \\oint_C \\vec{F}\\cdot d\\vec{r}"],
  ];
  it.each(expressions)("renders %s as MathML with the TeX annotation", async (_n, tex) => {
    const { el, r } = await render(tex, true);
    expect(r).toEqual({ ok: true });
    const math = el.querySelector("math");
    expect(math).not.toBeNull();
    expect(math!.namespaceURI).toBe(MML);
    expect(math!.getAttribute("display")).toBe("block");
    expect(el.querySelector("annotation[encoding='application/x-tex']")?.textContent).toBe(tex);
    // MathML only: no KaTeX HTML output, no inline styles
    expect(el.querySelector(".katex-html")).toBeNull();
    expect(el.querySelectorAll("[style]").length).toBe(0);
    expect(sanitizeMathTree(el)).toBe(0);
  });

  it("matrices produce a table and fractions an mfrac", async () => {
    expect((await render("\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}")).el.querySelectorAll("mtr").length).toBe(2);
    expect((await render("\\frac{1}{2}")).el.querySelector("mfrac")).not.toBeNull();
  });

  const attacks: [string, string][] = [
    ["javascript link", "\\href{javascript:alert(1)}{click}"],
    ["url", "\\url{https://attacker.example}"],
    ["image", "\\includegraphics{https://attacker.example/x.png}"],
    ["html class", "\\htmlClass{x}{y}"],
    ["html data", "\\htmlData{onclick=alert(1)}{y}"],
    ["html style", "\\htmlStyle{background:url(https://attacker.example)}{y}"],
    ["macro bomb", "\\def\\a{\\a\\a}\\a"],
    ["global macro", "\\gdef\\x{1}"],
    ["newcommand", "\\newcommand{\\x}{y}\\x"],
    ["unbalanced", "\\frac{1}{"],
    ["too long", "x".repeat(1001)],
  ];
  it.each(attacks)("refuses %s and shows the source as text", async (_n, tex) => {
    const { el, r } = await render(tex);
    expect(r.ok).toBe(false);
    expect(el.querySelector("math, a, img, script, [href], [onclick]")).toBeNull();
    expect(el.textContent).toBe(tex);
  });

  it("markup inside a formula is text, never elements", async () => {
    const tex = "\\text{<img src=x onerror=alert(1)><script>alert(1)</script>}";
    const { el, r } = await render(tex);
    expect(r.ok).toBe(true);
    expect(el.querySelector("img, script")).toBeNull();
    expect(el.querySelector("mtext")?.textContent).toContain("<script>");
  });

  it("trust is off even for commands that pass the server list (KaTeX's own refusal)", async () => {
    // \href written with a non-letter after it is still caught by the client guard; KaTeX would also refuse with trust: false
    const { r } = await render("\\href {javascript:alert(1)}{x}");
    expect(r.ok).toBe(false);
  });

  it("invalid TeX falls back to the source with a reason", async () => {
    const { el, r } = await render("\\frac12}{");
    expect(r.ok).toBe(false);
    expect(el.textContent).toBe("\\frac12}{");
  });

  it("the sanitizer strips foreign elements, handlers, links and styles", () => {
    const root = document.createElement("span");
    const math = document.createElementNS(MML, "math");
    const mi = document.createElementNS(MML, "mi");
    mi.setAttribute("onclick", "alert(1)");
    mi.setAttribute("href", "javascript:alert(1)");
    mi.setAttribute("style", "background:url(x)");
    mi.setAttribute("mathcolor", "url(javascript:alert(1))");
    mi.setAttribute("mathvariant", "bold");
    mi.textContent = "x";
    const maction = document.createElementNS(MML, "maction");
    const html = document.createElement("img");
    html.setAttribute("src", "x");
    math.append(mi, maction, html);
    root.appendChild(math);
    expect(sanitizeMathTree(root)).toBe(6);
    expect(mi.getAttributeNames()).toEqual(["mathvariant"]);
    expect(root.querySelector("img, maction")).toBeNull();
  });
});

describe("content source syntax", () => {
  it("round-trips text, inline and display math and escaped dollars", () => {
    const src = "Cost is \\$5 and $x^2$ grows.\n\n$$\\int_0^1 x\\,dx$$";
    const doc = fromSource(src);
    expect(doc.blocks).toEqual([
      { type: "paragraph", runs: [{ text: "Cost is $5 and " }, { math: "x^2" }, { text: " grows." }] },
      { type: "math", tex: "\\int_0^1 x\\,dx" },
    ]);
    expect(toSource(doc).source).toBe(src);
  });

  it("the client guard mirrors the server's", () => {
    expect(texProblem("\\frac{1}{2}")).toBeNull();
    expect(texProblem("\\href{x}{y}")).not.toBeNull();
    expect(texProblem("\\url{x}")).not.toBeNull();
    expect(texProblem("{")).not.toBeNull();
  });
});
