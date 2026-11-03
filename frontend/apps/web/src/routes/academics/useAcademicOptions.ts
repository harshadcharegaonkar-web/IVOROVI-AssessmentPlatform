import { unwrap } from "@ivorovi/api-client";
import { useQuery } from "@tanstack/react-query";
import { useApi } from "../../api/ApiProvider";

/** Option lists for filters and pickers (first 100, active first by name); the server applies the caller's scope. */
export function useProgramOptions(enabled = true) {
  const api = useApi();
  const q = useQuery({ queryKey: ["academics", "options", "programs"], enabled, staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/programs", { params: { query: { size: 100, sort: "name" } } })).items ?? [] });
  return q.data ?? [];
}

export function useCourseOptions(programId?: string, enabled = true) {
  const api = useApi();
  const q = useQuery({ queryKey: ["academics", "options", "courses", programId ?? ""], enabled, staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses", { params: { query: { size: 100, sort: "name", ...(programId ? { programId } : {}) } } })).items ?? [] });
  return q.data ?? [];
}

export function useYearOptions(enabled = true) {
  const api = useApi();
  const q = useQuery({ queryKey: ["academics", "options", "years"], enabled, staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-years", { params: { query: { size: 100, sort: "startDate", direction: "desc" } } })).items ?? [] });
  return q.data ?? [];
}

export function useTermOptions(academicYearId?: string, enabled = true) {
  const api = useApi();
  const q = useQuery({ queryKey: ["academics", "options", "terms", academicYearId ?? ""], enabled, staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-terms", { params: { query: { size: 100, sort: "startDate", direction: "desc",
      ...(academicYearId ? { academicYearId } : {}) } } })).items ?? [] });
  return q.data ?? [];
}

export function useSubjectOptions(q: string, courseId?: string, enabled = true) {
  const api = useApi();
  const r = useQuery({ queryKey: ["academics", "options", "subjects", q, courseId ?? ""], enabled, staleTime: 30_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subjects", { params: { query: { size: 50, sort: "code", status: "ACTIVE",
      ...(q ? { q } : {}), ...(courseId ? { courseId } : {}) } } })).items ?? [] });
  return { items: r.data ?? [], loading: r.isPending && enabled };
}
