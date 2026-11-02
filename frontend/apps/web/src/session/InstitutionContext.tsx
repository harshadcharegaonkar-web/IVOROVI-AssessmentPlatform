import type { OrgUnitTreeNode } from "@ivorovi/api-client";
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { readPreference, writePreference } from "../lib/preferences";
import { useOrgTree } from "./useOrgTree";

/**
 * "Current institution" (university or college) for the top-bar selector. It only pre-selects filters on screens
 * (dashboard, feature settings); it is never a security boundary. Every request is authorised by the API.
 */
interface InstitutionContextValue {
  options: OrgUnitTreeNode[];
  selectedId: string | null;
  select: (id: string | null) => void;
}

const Ctx = createContext<InstitutionContextValue>({ options: [], selectedId: null, select: () => {} });
const PREF = "institution";

export function InstitutionContextProvider({ children }: { children: ReactNode }) {
  const tree = useOrgTree();
  const [stored, setStored] = useState<string | null>(() => readPreference(PREF));
  const options = useMemo(
    () => (tree.data ?? []).filter((n) => (n.type === "UNIVERSITY" || n.type === "COLLEGE") && n.status === "ACTIVE"),
    [tree.data],
  );
  const selectedId = stored && options.some((o) => o.id === stored) ? stored : null;
  const select = useCallback((id: string | null) => {
    setStored(id);
    writePreference(PREF, id);
  }, []);
  const value = useMemo(() => ({ options, selectedId, select }), [options, selectedId, select]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useInstitutionContext(): InstitutionContextValue {
  return useContext(Ctx);
}
