import { unwrap, type StudentOption } from "@ivorovi/api-client";
import { Badge } from "@ivorovi/ui";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useApi } from "../../api/ApiProvider";
import { statusText, statusTone } from "./studentModel";

/** Every student query key starts with "students" so one invalidation refreshes lists, profiles and KPIs. */
export function useInvalidateStudents() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ["students"] });
}

export function StudentStatusBadge({ status }: { status: string | undefined }) {
  return <Badge tone={statusTone(status)}>{statusText(status)}</Badge>;
}

/** Built-in and organization-defined option lists (admission types and categories, document categories). */
export function useStudentOptions() {
  const api = useApi();
  const q = useQuery({ queryKey: ["students", "options"], staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/student-settings/options")) });
  const all = q.data ?? [];
  const of = (kind: StudentOption["kind"], includeInactive = false) => all.filter((o) => o.kind === kind && (includeInactive || o.active));
  return { all, of, loading: q.isPending };
}

/** Initials avatar for a student (photographs are shown only after they pass the malware scan). */
export function StudentAvatar({ name, size = "md" }: { name: string; size?: "md" | "lg" }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join("");
  return (
    <span aria-hidden className={size === "lg" ? "flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-primary-soft text-lg font-semibold text-primary"
      : "flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-soft text-sm font-semibold text-primary"}>{initials || "?"}</span>
  );
}
