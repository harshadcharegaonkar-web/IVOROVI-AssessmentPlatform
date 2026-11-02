import { unwrap, type InstitutionView } from "@ivorovi/api-client";
import { useQuery } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { useApi } from "../api/ApiProvider";
import { readPreference, writePreference } from "../lib/preferences";
import { useSession } from "./SessionProvider";

/**
 * "Current institution" context for the top-bar selector. It only pre-selects filters on screens (dashboard,
 * feature settings); it is never a security boundary. Every request is authorised by the API.
 */
interface InstitutionContextValue {
  options: InstitutionView[];
  selectedId: string | null;
  select: (id: string | null) => void;
}

const Ctx = createContext<InstitutionContextValue>({ options: [], selectedId: null, select: () => {} });
const PREF = "institution";

export function InstitutionContextProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const { access } = useSession();
  const canRead = access.hasAnywhere("institution.read");
  const query = useQuery({
    queryKey: ["institutions", "context-options"],
    enabled: canRead,
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { size: 100, sort: "name" } } })),
  });
  const [stored, setStored] = useState<string | null>(() => readPreference(PREF));
  const options = useMemo(() => query.data?.items ?? [], [query.data]);
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
