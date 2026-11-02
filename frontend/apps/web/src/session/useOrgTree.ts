import { unwrap, type OrgUnitTreeNode, type OrgUnitType } from "@ivorovi/api-client";
import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiProvider";
import { useSession } from "./SessionProvider";

/** A tree node with the fields the API always returns made non-optional. */
export type OrgNode = OrgUnitTreeNode & { id: string; type: OrgUnitType; name: string; code: string };

const complete = (n: OrgUnitTreeNode): n is OrgNode => !!n.id && !!n.type && n.name !== undefined && n.code !== undefined;

/** All organization units visible to the caller (flat, with parent ids); shared cache for pickers and the tree. */
export function useOrgTree() {
  const api = useApi();
  const { access } = useSession();
  return useQuery({
    queryKey: ["org-units", "tree"],
    enabled: access.hasAnywhere("org_unit.read"),
    staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/org-units/tree")).filter(complete),
  });
}

/** Ancestor ids of a unit, top-down, derived from the visible tree. */
export function ancestorIds(nodes: OrgUnitTreeNode[] | undefined, id: string | undefined | null): string[] {
  if (!nodes || !id) return [];
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out: string[] = [];
  let cur = byId.get(id)?.parentId;
  while (cur) {
    out.unshift(cur);
    cur = byId.get(cur)?.parentId;
  }
  return out;
}
