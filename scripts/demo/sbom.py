#!/usr/bin/env python3
"""Generates the library sections of docs/demo-programme/sbom.md (D-19) from what the build actually resolves.

  python3 scripts/demo/sbom.py            rewrite the generated part of sbom.md (between the markers)
  python3 scripts/demo/sbom.py --check    exit 1 if sbom.md is not current

Sources, no new tool or dependency:
  * Java: the API's resolved runtime classpath (gradle dependencies), licences from the POMs in the Gradle cache
    (following <parent> where a POM inherits its licence);
  * JavaScript: the production dependencies of every frontend package (pnpm licenses list --prod).
Container images and the approved D-19 components are maintained by hand in the top part of sbom.md.
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SBOM = os.path.join(ROOT, "docs", "demo-programme", "sbom.md")
CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
BEGIN, END = "<!-- generated:begin (scripts/demo/sbom.py) -->", "<!-- generated:end -->"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def java_components():
    out = subprocess.run([os.path.join(ROOT, "gradlew"), "-q", ":backend:api:dependencies", "--configuration", "runtimeClasspath"],
                         cwd=ROOT, capture_output=True, text=True, check=True).stdout
    found = {}
    for line in out.splitlines():
        m = re.search(r"--- ([\w.\-]+):([\w.\-]+):(\S+)", line)
        if not m or line.rstrip().endswith("(n)"):
            continue
        group, artifact, version = m.group(1), m.group(2), m.group(3)
        if "->" in line:
            version = line.split("->")[1].split()[0]
        if version in ("(c)", "(*)"):
            continue
        found[(group, artifact)] = version.split()[0]
    return sorted((g, a, v) for (g, a), v in found.items())


def pom(group, artifact, version):
    d = os.path.join(CACHE, group, artifact, version)
    if not os.path.isdir(d):
        return None
    for h in os.listdir(d):
        p = os.path.join(d, h, f"{artifact}-{version}.pom")
        if os.path.exists(p):
            try:
                return ET.parse(p).getroot()
            except ET.ParseError:
                return None
    return None


def licence(group, artifact, version, depth=0):
    root = pom(group, artifact, version)
    if root is None or depth > 6:
        return "UNKNOWN (POM not in cache)" if depth == 0 else None
    names = [l.findtext("m:name", default="", namespaces=NS).strip() for l in root.findall("m:licenses/m:license", NS)]
    names = [n for n in names if n]
    if names:
        return "; ".join(dict.fromkeys(names))
    parent = root.find("m:parent", NS)
    if parent is not None:
        got = licence(parent.findtext("m:groupId", namespaces=NS), parent.findtext("m:artifactId", namespaces=NS),
                      parent.findtext("m:version", namespaces=NS), depth + 1)
        if got:
            return got
    return "UNKNOWN (no licence in POM)" if depth == 0 else None


def js_components():
    out = subprocess.run(["pnpm", "licenses", "list", "--prod", "--json", "-r"], cwd=os.path.join(ROOT, "frontend"),
                         capture_output=True, text=True, check=True).stdout
    rows = []
    for lic, pkgs in json.loads(out).items():
        for p in pkgs:
            for v in p.get("versions", []):
                rows.append((p["name"], v, lic))
    return sorted(set(rows))


def generated():
    java = java_components()
    js = js_components()
    lines = [BEGIN, "", f"### Java runtime libraries of the API ({len(java)})", "",
             "Resolved `runtimeClasspath` of `backend/api`; licence as declared in the artifact's POM (or its parent).", "",
             "| Group | Artifact | Version | Licence |", "|---|---|---|---|"]
    lines += [f"| {g} | {a} | {v} | {licence(g, a, v)} |" for g, a, v in java]
    lines += ["", f"### JavaScript production dependencies of the frontend ({len(js)})", "",
              "`pnpm licenses list --prod -r` over `frontend/` (staff app, exam app and shared packages); bundled into the apps.", "",
              "| Package | Version | Licence |", "|---|---|---|"]
    lines += [f"| {n} | {v} | {l} |" for n, v, l in js]
    lines += ["", END]
    return "\n".join(lines)


def main():
    text = open(SBOM, encoding="utf-8").read()
    if BEGIN not in text or END not in text:
        sys.exit("sbom.md has no generated section markers")
    new = text[:text.index(BEGIN)] + generated() + text[text.index(END) + len(END):]
    if "--check" in sys.argv:
        if new != text:
            sys.exit("docs/demo-programme/sbom.md is not current: run python3 scripts/demo/sbom.py")
        print("sbom.md is current")
        return
    open(SBOM, "w", encoding="utf-8").write(new)
    unknown = new.count("UNKNOWN")
    print(f"sbom.md updated ({unknown} component(s) with an unknown licence)")


if __name__ == "__main__":
    main()
