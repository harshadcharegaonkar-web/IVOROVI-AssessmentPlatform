import { render, screen, waitFor } from "@testing-library/react";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ContentView } from "./ContentView";
import { CONTENT_FORMAT, type ContentDoc } from "./format";

describe("ContentView", () => {
  it("renders text as text, never as markup", () => {
    const doc: ContentDoc = { format: CONTENT_FORMAT, blocks: [{ type: "paragraph", runs: [{ text: "<img src=x onerror=alert(1)><script>alert(1)</script>" }] }] };
    const { container } = render(<ContentView doc={doc} />);
    expect(container.querySelector("img, script")).toBeNull();
    expect(container.textContent).toContain("<script>alert(1)</script>");
  });

  it("renders inline math as MathML and images with their description", async () => {
    const doc: ContentDoc = { format: CONTENT_FORMAT, blocks: [
      { type: "paragraph", runs: [{ text: "Evaluate " }, { math: "\\frac{1}{2}" }, { text: "bold", marks: ["bold"] }] },
      { type: "image", asset: "a1", alt: "Right triangle with sides 3, 4 and 5" },
      { type: "image", asset: "a2", decorative: true },
    ] };
    const { container } = render(<ContentView doc={doc} imageSrc={(id) => (id === "a1" ? "data:image/png;base64,iVBORw0KGgo=" : undefined)} />);
    await waitFor(() => expect(container.querySelector("math")).not.toBeNull());
    expect(screen.getByRole("img", { name: "Right triangle with sides 3, 4 and 5" })).toBeInTheDocument();
    expect(container.querySelector("strong")?.textContent).toBe("bold");
  });

  it("shows the formula source with an accessible label when a formula is refused", async () => {
    const doc: ContentDoc = { format: CONTENT_FORMAT, blocks: [{ type: "math", tex: "\\href{javascript:alert(1)}{x}" }] };
    const { container } = render(<ContentView doc={doc} />);
    await waitFor(() => expect(container.querySelector("[data-math='fallback']")).not.toBeNull());
    expect(screen.getByRole("img", { name: "Formula: \\href{javascript:alert(1)}{x}" })).toBeInTheDocument();
    expect(container.querySelector("a")).toBeNull();
  });

  it("no source file uses dangerouslySetInnerHTML or innerHTML", () => {
    const dir = __dirname;
    for (const f of readdirSync(dir).filter((n) => /\.(ts|tsx)$/.test(n) && !n.includes(".test."))) {
      // code only: comments may name the APIs they avoid
      const text = readFileSync(join(dir, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
      expect(text, f).not.toMatch(/dangerouslySetInnerHTML|innerHTML|outerHTML|insertAdjacentHTML|document\.write/);
    }
  });
});
