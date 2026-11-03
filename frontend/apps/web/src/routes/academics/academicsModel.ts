import type { AcademicTree, Program } from "@ivorovi/api-client";
import type { BadgeTone } from "@ivorovi/ui";

/** Pure helpers of the academic screens (no React), unit-tested in academicsModel.test.ts. */

export type QualificationLevel = NonNullable<Program["qualificationLevel"]>;
export type TermSystem = NonNullable<Program["termSystem"]>;
export type Delivery = "THEORY" | "PRACTICAL" | "THEORY_AND_PRACTICAL";
export type Category = "CORE" | "ELECTIVE" | "OPEN_ELECTIVE" | "FOUNDATION" | "LABORATORY" | "PROJECT" | "SEMINAR" | "INTERNSHIP" | "AUDIT" | "OTHER";

export const QUALIFICATION_LEVELS: { value: QualificationLevel; label: string }[] = [
  { value: "CERTIFICATE", label: "Certificate" }, { value: "DIPLOMA", label: "Diploma" }, { value: "UNDERGRADUATE", label: "Undergraduate" },
  { value: "POSTGRADUATE", label: "Postgraduate" }, { value: "DOCTORAL", label: "Doctoral" }, { value: "OTHER", label: "Other" },
];

/** Term systems and how many terms each has per academic year (OTHER: the organization says). */
export const TERM_SYSTEMS: { value: TermSystem; label: string; perYear: number | null }[] = [
  { value: "SEMESTER", label: "Semesters (2 per year)", perYear: 2 }, { value: "TRIMESTER", label: "Trimesters (3 per year)", perYear: 3 },
  { value: "QUARTER", label: "Quarters (4 per year)", perYear: 4 }, { value: "ANNUAL", label: "Annual (1 per year)", perYear: 1 },
  { value: "OTHER", label: "Other", perYear: null },
];

export const DELIVERY: { value: Delivery; label: string }[] = [
  { value: "THEORY", label: "Theory" }, { value: "PRACTICAL", label: "Practical" }, { value: "THEORY_AND_PRACTICAL", label: "Theory and practical" },
];

export const CATEGORIES: { value: Category; label: string }[] = [
  { value: "CORE", label: "Core" }, { value: "ELECTIVE", label: "Elective" }, { value: "OPEN_ELECTIVE", label: "Open elective" },
  { value: "FOUNDATION", label: "Foundation" }, { value: "LABORATORY", label: "Laboratory" }, { value: "PROJECT", label: "Project" },
  { value: "SEMINAR", label: "Seminar" }, { value: "INTERNSHIP", label: "Internship" }, { value: "AUDIT", label: "Audit" }, { value: "OTHER", label: "Other" },
];

export function labelOf<T extends string>(list: { value: T; label: string }[], value: T | undefined | null): string {
  return list.find((o) => o.value === value)?.label ?? (value ?? "—");
}

const TONES: Record<string, BadgeTone> = {
  ACTIVE: "success", INACTIVE: "warning", PLANNED: "info", COMPLETED: "neutral", GRADUATED: "neutral", CANCELLED: "danger",
};
export function statusTone(status: string | undefined): BadgeTone {
  return TONES[status ?? ""] ?? "neutral";
}

export function statusLabel(status: string | undefined): string {
  if (!status) return "—";
  return status.charAt(0) + status.slice(1).toLowerCase();
}

/** Terms per year for a system (mirrors the server rule). */
export function termsPerYear(system: TermSystem, custom?: number | null): number | null {
  return TERM_SYSTEMS.find((t) => t.value === system)?.perYear ?? (custom && custom > 0 ? custom : null);
}

/** Default number of terms of a program: duration × terms per year, rounded up (mirrors the server). */
export function defaultTotalTerms(system: TermSystem, durationYears: number, custom?: number | null): number | null {
  const perYear = termsPerYear(system, custom);
  if (!perYear || !(durationYears > 0)) return null;
  return Math.ceil(Math.round(durationYears * perYear * 10) / 10);
}

/** Default expected graduation year of a batch: admission year + program duration (rounded up). */
export function defaultGraduationYear(admissionYear: number, durationYears: number | undefined): number | null {
  if (!Number.isInteger(admissionYear) || !durationYears) return null;
  return admissionYear + Math.ceil(durationYears);
}

/** Inclusive date ranges (ISO yyyy-mm-dd) that share at least one day. */
export function rangesOverlap(aStart: string, aEnd: string, bStart: string, bEnd: string): boolean {
  return aStart <= bEnd && bStart <= aEnd;
}

/** Date-only ISO strings are formatted without time-zone shifts. */
export function formatDate(iso: string | undefined | null): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return iso;
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

export function formatRange(start: string | undefined | null, end: string | undefined | null): string {
  if (!start && !end) return "—";
  return `${formatDate(start)} – ${formatDate(end)}`;
}

/** Curriculum grouped by term number, terms in order (empty terms included up to {@code terms}). */
export function groupByTerm<T extends { termNumber?: number }>(entries: T[], terms: number): { term: number; entries: T[] }[] {
  const max = Math.max(terms, ...entries.map((e) => e.termNumber ?? 0));
  return Array.from({ length: max }, (_, i) => ({ term: i + 1, entries: entries.filter((e) => e.termNumber === i + 1) }));
}

export interface StructureNode { id: string; parentId: string | null; label: string; kind: "program" | "course" | "batch"; status: string; meta: string; to: string }

/** Program → course → batch tree flattened for the tree view. */
export function treeNodes(tree: AcademicTree | undefined): StructureNode[] {
  const out: StructureNode[] = [];
  for (const p of tree?.programs ?? []) {
    out.push({ id: `p:${p.id}`, parentId: null, label: `${p.name} (${p.code})`, kind: "program", status: p.status ?? "",
      meta: `${p.ownerName ?? ""} · ${p.totalTerms ?? 0} terms`, to: `/academics/programs/${p.id}` });
    for (const c of p.courses ?? []) {
      out.push({ id: `c:${c.id}`, parentId: `p:${p.id}`, label: `${c.name} (${c.code})`, kind: "course", status: c.status ?? "",
        meta: `${c.departmentName ?? ""} · ${c.subjects ?? 0} subjects`, to: `/academics/courses/${c.id}` });
      for (const b of c.batches ?? []) {
        out.push({ id: `b:${b.id}`, parentId: `c:${c.id}`, label: `${b.name} (${b.code})`, kind: "batch", status: b.status ?? "",
          meta: `Admitted ${b.admissionYear}${b.section ? ` · Section ${b.section}` : ""} · ${b.offerings ?? 0} offerings`, to: `/academics/batches/${b.id}` });
      }
    }
  }
  return out;
}

/** Statuses an offering can move to from its current one (mirrors the server). */
export function offeringTransitions(status: string | undefined): ("PLANNED" | "ACTIVE" | "COMPLETED" | "CANCELLED")[] {
  if (status === "PLANNED") return ["ACTIVE", "CANCELLED"];
  if (status === "ACTIVE") return ["COMPLETED", "PLANNED", "CANCELLED"];
  return [];
}

/** Calendar transitions: forward, plus corrections that need a reason. */
export function calendarTransitions(status: string | undefined): { to: "PLANNED" | "ACTIVE" | "COMPLETED"; correction: boolean }[] {
  if (status === "PLANNED") return [{ to: "ACTIVE", correction: false }];
  if (status === "ACTIVE") return [{ to: "COMPLETED", correction: false }, { to: "PLANNED", correction: true }];
  if (status === "COMPLETED") return [{ to: "ACTIVE", correction: true }];
  return [];
}

export const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._/-]*$/;
