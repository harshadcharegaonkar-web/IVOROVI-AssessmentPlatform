import { unwrap, type OrgUnitType } from "@ivorovi/api-client";
import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiProvider";

/** Academic levels whose names an organization can change (Program → Course → Subject, terms and batches). */
export type AcademicTermKey = "PROGRAM" | "COURSE" | "SUBJECT" | "TERM" | "BATCH";
/** Every configurable name: the stable unit types and the academic levels (internal types never change). */
export type TerminologyKey = OrgUnitType | AcademicTermKey;

export const TERMINOLOGY_DEFAULTS: Record<TerminologyKey, { singular: string; plural: string }> = {
  UNIVERSITY: { singular: "University", plural: "Universities" },
  COLLEGE: { singular: "College", plural: "Colleges" },
  CAMPUS: { singular: "Campus", plural: "Campuses" },
  DEPARTMENT: { singular: "Department", plural: "Departments" },
  PROGRAM: { singular: "Program", plural: "Programs" },
  COURSE: { singular: "Course", plural: "Courses" },
  SUBJECT: { singular: "Subject", plural: "Subjects" },
  TERM: { singular: "Semester", plural: "Semesters" },
  BATCH: { singular: "Batch", plural: "Batches" },
};

/** Organization-specific display labels (System Settings). */
export function useOrgLabels() {
  const api = useApi();
  const q = useQuery({
    queryKey: ["tenant-settings"],
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/tenant/settings")),
  });
  const labels = (q.data?.labels ?? {}) as Partial<Record<TerminologyKey, { singular?: string; plural?: string }>>;
  const singular = (t: TerminologyKey) => labels[t]?.singular ?? TERMINOLOGY_DEFAULTS[t].singular;
  const plural = (t: TerminologyKey) => labels[t]?.plural ?? TERMINOLOGY_DEFAULTS[t].plural;
  return { singular, plural, settings: q.data, loading: q.isPending };
}
