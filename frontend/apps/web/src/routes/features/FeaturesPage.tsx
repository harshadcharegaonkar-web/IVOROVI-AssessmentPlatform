import { unwrap } from "@ivorovi/api-client";
import { Field, PageHeader, Select } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { FeatureConfigurationPanel } from "./FeatureConfigurationPanel";

export function FeaturesPage() {
  const api = useApi();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const tenantWide = access.has("feature.read");
  const scoped = access.institutionsWith("feature.read");

  const institutions = useQuery({
    queryKey: ["institutions", "all-for-features"],
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { size: 100, sort: "name" } } })),
  });
  const options = (institutions.data?.items ?? []).filter((i) => tenantWide || scoped.includes(i.id!));
  const requested = params.get("institution");
  const institutionId = requested && options.some((o) => o.id === requested) ? requested : tenantWide ? null : options[0]?.id ?? null;
  const name = options.find((o) => o.id === institutionId)?.name;

  return (
    <>
      <PageHeader title="Features" description="Control which modules, assessment types, AI capabilities and integrations are available." />
      <div className="mb-4 max-w-sm">
        <Field label="Scope" hint="Institutions can switch off features enabled for the organization, but cannot exceed it.">
          {(p) => (
            <Select {...p} value={institutionId ?? ""} onChange={(e) => setParams(e.target.value ? { institution: e.target.value } : {})}>
              {tenantWide && <option value="">Whole organization</option>}
              {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
            </Select>
          )}
        </Field>
      </div>
      {(tenantWide || institutionId) && (
        <FeatureConfigurationPanel key={institutionId ?? "tenant"} institutionId={institutionId} {...(name ? { institutionName: name } : {})} />
      )}
    </>
  );
}
