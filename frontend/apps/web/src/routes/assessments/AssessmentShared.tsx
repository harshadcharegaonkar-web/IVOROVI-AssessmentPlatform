import { Badge, TabPanel, Tabs, type BadgeTone } from "@ivorovi/ui";
import type { ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import { useSession } from "../../session/SessionProvider";

export const PURPOSES: [string, string][] = [
  ["INTERNAL", "Internal assessment"], ["PRACTICE", "Practice"], ["SEMESTER", "Semester examination"],
  ["PLACEMENT", "Placement test"], ["APTITUDE", "Aptitude test"], ["ADMISSION", "Admission examination"],
];
export const PURPOSE_LABEL: Record<string, string> = Object.fromEntries(PURPOSES);
/** Q-2.2-6: holders of assessment.author.internal (faculty) author these purposes only. */
export const INTERNAL_PURPOSES = ["INTERNAL", "PRACTICE"];
/** Q-2.2-2: always three different people (author, reviewer, publisher). */
export const HIGH_STAKES = ["ADMISSION", "SEMESTER", "PLACEMENT", "APTITUDE"];

const STATE_TONE: Record<string, BadgeTone> = {
  DRAFT: "neutral", IN_REVIEW: "info", APPROVED: "info", PUBLISHED: "success", SUPERSEDED: "neutral", WITHDRAWN: "warning",
};
export const STATE_LABEL: Record<string, string> = {
  DRAFT: "Draft", IN_REVIEW: "In review", APPROVED: "Approved", PUBLISHED: "Published", SUPERSEDED: "Superseded", WITHDRAWN: "Withdrawn",
};

export function AssessmentStateBadge({ state }: { state?: string | null }) {
  const s = state ?? "DRAFT";
  return <Badge tone={STATE_TONE[s] ?? "neutral"}>{STATE_LABEL[s] ?? s}</Badge>;
}

export function GovernanceBadge({ governance }: { governance?: string | null }) {
  return governance === "HIGH_STAKES" ? <Badge tone="warning">High-stakes · 3 people</Badge> : <Badge tone="neutral">Standard · 2 people</Badge>;
}

export function marks(v?: number | null): string {
  if (v === undefined || v === null) return "—";
  return Number.isInteger(v) ? String(v) : v.toFixed(2).replace(/0$/, "");
}

/** Assessment section switcher: list, and the queue for reviewers and publishers. */
export function AssessmentTabs({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const { access } = useSession();
  const queue = access.hasAnywhere("assessment.review") || access.hasAnywhere("assessment.publish");
  const value = pathname.startsWith("/assessments/queue") ? "/assessments/queue" : "/assessments";
  return (
    <Tabs label="Assessment areas" value={value} onValueChange={(v) => void navigate(v)}
      tabs={[{ value: "/assessments", label: "Assessments" }, ...(queue ? [{ value: "/assessments/queue", label: "Review & publication" }] : [])]}>
      <TabPanel value={value}>{children}</TabPanel>
    </Tabs>
  );
}
