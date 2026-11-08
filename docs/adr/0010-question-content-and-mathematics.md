# ADR-0010 — Question content format, mathematics (KaTeX, MathML only) and images

Status: accepted for Increment 2.1. KaTeX 0.16 approved 2026-11-08 (L-2.1-1). Date: 2026-11-08.

## Context

Questions need rich text, mathematical notation and images, without ever running HTML or JavaScript from content. This
covers the staff application now and the candidate exam application later, whose CSP forbids inline styles.

## Decision

**Content format `ivorovi.content.v1`, never HTML.**
- A structured document of paragraphs (text runs with bold, italic, sub and sup marks, plus inline math runs), display
  math, image references and lists.
- The server (`ContentFormat`) rebuilds every document from allowed keys only. It rejects unknown keys and block types,
  control and direction-override characters, and oversize content.
- Renderers build DOM nodes from this structure. Text always becomes text nodes, and no application code uses
  `innerHTML` or `dangerouslySetInnerHTML` (a test scans the content package).
- Editors and imports use a source syntax: `$…$` inline, `$$…$$` display, `\$` for a dollar sign.

**Mathematics.** KaTeX **0.16.47**, pinned in the lockfile, bundled into the application and served by it (no CDN). It
is loaded lazily, only on pages that show a formula, so it is never in the initial bundle (enforced by the budget
script). Layered defences:
1. **TeX rules on the server (`TexGuard`), mirrored on the client:**
   - at most 1,000 characters per formula;
   - balanced braces;
   - no control characters;
   - refuses commands that load content, define macros or touch files: `\href`, `\url`, `\includegraphics`, `\html…`,
     `\def` and its variants, `\newcommand`, `\let`, `\csname`, `\char`, `\verb`, `\input`, `\write`, and similar.
2. **KaTeX options:**
   - `output: "mathml"` (no HTML output);
   - `trust: false` (links, images and HTML extensions disabled);
   - a fresh macro table per formula, `globalGroup: false`;
   - `maxSize: 20`, `maxExpand: 200`;
   - `throwOnError: true`.
3. **DOM construction, no HTML parsing:** `katex.render` builds DOM nodes with `createElement`/`textContent` (the
   library contains no `innerHTML`).
4. **MathML allowlist sanitizer:** after rendering, only allowlisted MathML elements and attributes remain. Values are
   restricted to a plain character set; `xmlns` and `encoding` must have their exact KaTeX values. There are no event
   handlers, links, styles or foreign elements.

The output is native MathML with the TeX source kept as an annotation, which browsers expose to assistive technology.
It contains no `style` attributes, so the exam CSP (`style-src 'self'`) holds unchanged. **No CSP exception was added.**

**Fallback behaviour.** If KaTeX cannot be loaded (network or chunk failure), or a formula is refused or fails to
parse, the formula's TeX source is shown as plain text with `role="img"` and an accessible name "Formula: …", and a
tooltip explains why. Content is never lost or hidden. A failed load is retried on the next formula. Browsers without
native MathML (pre-2023 Chromium) show unstyled MathML text; all supported browsers render MathML natively. Rendering
quality depends on the installed math font (`font-family: math`; no web font is loaded, see KI-76).

**Images.**
- PNG and JPEG only, recognized by magic bytes.
- At most 5 MB and 4096 × 4096 per image; at most 15 MB of images per question; at most 10 images per version.
- Stored in quarantine first and scanned (fail closed: outside development and test, no scanner means no upload).
  Only CLEAN images are re-encoded from decoded pixels, which drops metadata and trailing data, and served.
- Institution quotas apply.
- Served only to readers of the bank (404 otherwise, audited), with `nosniff`, `Content-Disposition: inline`,
  `CSP: sandbox; default-src 'none'`, `CORP: same-origin` and private caching.
- The staff UI fetches images with the bearer token and shows them as `data:` URLs (already allowed by the CSP; no
  `blob:` exception).
- Unused images are purged after 30 days only when no question version links them. A database trigger refuses to
  delete a linked image. Assessments reference versions, and versions keep their links.

## Consequences

- A small, auditable surface: one renderer package (`@ivorovi/content`) shared by the staff app and, from 2.5, the exam
  app.
- KaTeX's lazy chunk is 74.6 kB gzip. It has its own budget of ≤ 80 kB (approved 2026-11-08, L-2.1-2; any increase needs separate approval) instead of the
  general 40 kB lazy-chunk limit.
- Visual quality varies with the platform's math font. Accessibility relies on native MathML support in screen readers
  (VoiceOver, NVDA with MathCAT, JAWS, TalkBack). Automated checks cover the accessibility tree and axe; manual
  screen-reader verification is tracked as KI-77.
