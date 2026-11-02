import { Field, PageHeader, Select } from "@ivorovi/ui";
import { useSearchParams } from "react-router";
import { useInstitutionContext } from "../../session/InstitutionContext";
import { useSession } from "../../session/SessionProvider";
import { ancestorIds, useOrgTree } from "../../session/useOrgTree";
import { useOrgLabels } from "../../session/useOrgLabels";
import { FeatureConfigurationPanel } from "./FeatureConfigurationPanel";

/** Feature settings for the organization, or for one university/college (inherited by its sub-units). */
export function FeaturesPage() {
  const { access } = useSession();
  const labels = useOrgLabels();
  const tree = useOrgTree();
  const [params, setParams] = useSearchParams();
  const { selectedId } = useInstitutionContext();
  const tenantWide = access.has("feature.read");
  const nodes = tree.data ?? [];
  const options = nodes.filter((n) => (n.type === "UNIVERSITY" || n.type === "COLLEGE")
    && access.hasInOrgUnit("feature.read", n.id, ancestorIds(nodes, n.id)));
  const requested = params.get("unit") ?? selectedId;
  const orgUnitId = requested && options.some((o) => o.id === requested) ? requested : tenantWide ? null : options[0]?.id ?? null;
  const name = options.find((o) => o.id === orgUnitId)?.name;

  return (
    <>
      <PageHeader title="Features" description="Control which modules, assessment types, AI capabilities and integrations are available." />
      <div className="mb-4 max-w-sm">
        <Field label="Scope" hint={`${labels.plural("CAMPUS")} and ${labels.plural("DEPARTMENT").toLowerCase()} inherit from their institution. Lower levels can switch features off, never on.`}>
          {(p) => (
            <Select {...p} value={orgUnitId ?? ""} onChange={(e) => setParams(e.target.value ? { unit: e.target.value } : {})}>
              {tenantWide && <option value="">Whole organization</option>}
              {options.map((o) => <option key={o.id} value={o.id}>{o.name} ({labels.singular(o.type!)})</option>)}
            </Select>
          )}
        </Field>
      </div>
      {(tenantWide || orgUnitId) && (
        <FeatureConfigurationPanel key={orgUnitId ?? "tenant"} orgUnitId={orgUnitId}
          ancestorIds={ancestorIds(nodes, orgUnitId)} {...(name ? { unitName: name } : {})} />
      )}
    </>
  );
}

export default FeaturesPage;
