import type { FeatureConfigurationView } from "@ivorovi/api-client";

const CATEGORY_TITLES: Record<string, string> = {
  MODULE: "Modules",
  ASSESSMENT_TYPE: "Assessment types",
  AI: "AI capabilities",
  INTEGRATION: "Integrations",
};

export function groupByCategory(flags: FeatureConfigurationView[]) {
  return Object.keys(CATEGORY_TITLES)
    .map((category) => ({ category, title: CATEGORY_TITLES[category]!, flags: flags.filter((f) => f.category === category) }))
    .filter((g) => g.flags.length > 0);
}

/** Enabling an AI capability is consequential and requires explicit confirmation. */
export function nextActionFor(flag: FeatureConfigurationView, enabled: boolean): "confirm" | "apply" {
  return enabled && flag.category === "AI" ? "confirm" : "apply";
}

export function describeSource(f: FeatureConfigurationView): string {
  switch (f.source) {
    case "DEPLOYMENT": return "Disabled for this deployment";
    case "INSTITUTION": return "Set for this institution";
    case "TENANT": return "Set for the organization";
    default: return `Default (${f.defaultEnabled ? "on" : "off"})`;
  }
}
