import { unwrap, type OrgUnitType } from "@ivorovi/api-client";
import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiProvider";

const DEFAULTS: Record<OrgUnitType, { singular: string; plural: string }> = {
  UNIVERSITY: { singular: "University", plural: "Universities" },
  COLLEGE: { singular: "College", plural: "Colleges" },
  CAMPUS: { singular: "Campus", plural: "Campuses" },
  DEPARTMENT: { singular: "Department", plural: "Departments" },
};

/** Organization-specific display labels for the stable unit types (System Settings). */
export function useOrgLabels() {
  const api = useApi();
  const q = useQuery({
    queryKey: ["tenant-settings"],
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/tenant/settings")),
  });
  const labels = (q.data?.labels ?? {}) as Partial<Record<OrgUnitType, { singular?: string; plural?: string }>>;
  const singular = (t: OrgUnitType) => labels[t]?.singular ?? DEFAULTS[t].singular;
  const plural = (t: OrgUnitType) => labels[t]?.plural ?? DEFAULTS[t].plural;
  return { singular, plural, settings: q.data, loading: q.isPending };
}
