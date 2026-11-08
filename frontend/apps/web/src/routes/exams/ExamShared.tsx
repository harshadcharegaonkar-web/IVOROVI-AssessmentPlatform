import { TabPanel, Tabs } from "@ivorovi/ui";
import type { ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";

/** Exams section switcher (Increment 2.0 foundations: portals, readiness sessions, candidates, incidents). */
export function ExamTabs({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const areas = ["/exams/sessions", "/exams/candidates", "/exams/incidents"];
  const value = areas.find((p) => pathname.startsWith(p)) ?? "/exams/portals";
  return (
    <Tabs label="Exam areas" value={value} onValueChange={(v) => void navigate(v)}
      tabs={[{ value: "/exams/portals", label: "Portals" }, { value: "/exams/sessions", label: "Readiness sessions" },
        { value: "/exams/candidates", label: "Candidates" }, { value: "/exams/incidents", label: "Incidents" }]}>
      <TabPanel value={value}>{children}</TabPanel>
    </Tabs>
  );
}

/** datetime-local value (local time) ⇄ ISO instant. */
export const toLocalInput = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
export const fromLocalInput = (v: string) => new Date(v).toISOString();
