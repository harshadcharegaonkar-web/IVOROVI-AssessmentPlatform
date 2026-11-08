// Bundle boundary check (C-4): the exam build must contain no staff application code. Every source of every shipped
// JavaScript file is read from the source maps; any module from the staff app or the staff API client fails the build,
// as does any staff API path or staff OIDC client id in the shipped code.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const DIST = new URL("../dist/assets/", import.meta.url).pathname;
const FORBIDDEN_SOURCES = [/apps[\\/]web[\\/]/, /packages[\\/]api-client[\\/]/, /react-oidc-context/, /react-router/, /@tanstack/];
const FORBIDDEN_STRINGS = ["/api/v1/students", "/api/v1/me", "/api/v1/exam/", "/api/v1/approvals", "ivorovi-web", "realms/ivorovi\""];

const problems = [];
let sources = 0;
for (const f of readdirSync(DIST)) {
  if (f.endsWith(".js.map")) {
    const map = JSON.parse(readFileSync(join(DIST, f), "utf8"));
    for (const s of map.sources ?? []) {
      sources++;
      if (FORBIDDEN_SOURCES.some((r) => r.test(s))) problems.push(`${f}: staff module ${s}`);
    }
  } else if (f.endsWith(".js")) {
    const code = readFileSync(join(DIST, f), "utf8");
    for (const s of FORBIDDEN_STRINGS) if (code.includes(s)) problems.push(`${f}: contains "${s}"`);
  }
}
console.log(`Exam bundle boundary: ${sources} source modules checked`);
if (problems.length) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log("OK: no staff application code, staff API paths or staff client ids in the exam bundle");
