// Exam application bundle budget (Increment 2.0 plan §7: ≤ 120 kB initial gzip, its own budget separate from the staff app).
//
// Initial payload = every JavaScript file the browser must load before the first screen renders:
//   the entry script(s) referenced by dist/index.html, plus everything they import *statically*, recursively
//   (shared vendor chunks, runtime). Dynamic imports (lazy pages, the search palette) are excluded; source maps
//   (*.map) are never counted.
//
// Enforced metric (CI): sum of per-file gzip sizes (zlib level 9) of the initial payload ≤ 180 kB, and every lazily
// loaded chunk ≤ 40 kB gzip (page target: largest page 36 kB at approval). Raw and Brotli sizes are reported for
// information.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";

const DIST = new URL("../dist/", import.meta.url).pathname;
const INITIAL_BUDGET_KB = 120;
const LAZY_CHUNK_BUDGET_KB = 40;

const read = (file) => readFileSync(join(DIST, file));
const sizes = (file) => {
  const buf = read(file);
  return {
    raw: buf.length / 1024,
    gzip: gzipSync(buf, { level: 9 }).length / 1024,
    brotli: brotliCompressSync(buf, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length / 1024,
  };
};

// Static import specifiers in rolldown/esbuild output: `import{a}from"./x.js"`, `import"./x.js"`, `export*from"./x.js"`.
const STATIC_IMPORT = /(?:^|[^\w$.])(?:import|export)\s*(?:[\w$*{},\s]+?\s*from\s*)?["'](\.\/[^"']+\.js)["']/g;

const html = read("index.html").toString();
const entries = [...html.matchAll(/<script[^>]+src="\/(assets\/[^"]+\.js)"/g)].map((m) => m[1]);
const initial = new Set();
const queue = [...entries];
while (queue.length) {
  const file = queue.shift();
  if (initial.has(file)) continue;
  initial.add(file);
  const code = read(file).toString();
  for (const m of code.matchAll(STATIC_IMPORT)) queue.push(join("assets", m[1].replace("./", "")));
}

const all = readdirSync(join(DIST, "assets")).filter((f) => f.endsWith(".js")).map((f) => `assets/${f}`);
const lazy = all.filter((f) => !initial.has(f)).map((f) => ({ f, ...sizes(f) })).sort((a, b) => b.gzip - a.gzip);
const init = [...initial].map((f) => ({ f, ...sizes(f) })).sort((a, b) => b.gzip - a.gzip);
const total = init.reduce((t, c) => ({ raw: t.raw + c.raw, gzip: t.gzip + c.gzip, brotli: t.brotli + c.brotli }), { raw: 0, gzip: 0, brotli: 0 });
const fmt = (n) => n.toFixed(1).padStart(7);

console.log(`Initial JavaScript payload: ${init.length} files (entry + static imports, no source maps)`);
console.log(`  ${"file".padEnd(48)}     raw    gzip  brotli (kB)`);
for (const c of init) console.log(`  ${c.f.padEnd(48)} ${fmt(c.raw)} ${fmt(c.gzip)} ${fmt(c.brotli)}`);
console.log(`  ${"TOTAL".padEnd(48)} ${fmt(total.raw)} ${fmt(total.gzip)} ${fmt(total.brotli)}`);
console.log(`Enforced: initial gzip ${total.gzip.toFixed(1)} kB ≤ ${INITIAL_BUDGET_KB} kB`);
console.log(`Lazy chunks: ${lazy.length}; largest ${lazy[0] ? `${lazy[0].f} ${lazy[0].gzip.toFixed(1)} kB gzip` : "-"} (≤ ${LAZY_CHUNK_BUDGET_KB} kB each)`);
for (const c of lazy.slice(0, 5)) console.log(`  ${c.f.padEnd(48)} ${fmt(c.raw)} ${fmt(c.gzip)} ${fmt(c.brotli)}`);

let failed = false;
if (total.gzip > INITIAL_BUDGET_KB) { console.error(`FAIL: initial JS ${total.gzip.toFixed(1)} kB gzip exceeds ${INITIAL_BUDGET_KB} kB`); failed = true; }
for (const c of lazy) if (c.gzip > LAZY_CHUNK_BUDGET_KB) { console.error(`FAIL: ${c.f} ${c.gzip.toFixed(1)} kB gzip exceeds ${LAZY_CHUNK_BUDGET_KB} kB`); failed = true; }
process.exit(failed ? 1 : 0);
