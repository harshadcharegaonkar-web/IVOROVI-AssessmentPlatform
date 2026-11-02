export { createApiClient, unwrap, type ApiClientOptions, type IvoroviApi } from "./client";
export { ApiError, type Problem } from "./errors";
export type { components, paths } from "./generated/schema";

import type { components } from "./generated/schema";
type Schemas = components["schemas"];
export type MeResponse = Schemas["MeResponse"];
export type FeatureConfigurationView = Schemas["FeatureConfigurationView"];
export type EffectiveFeature = Schemas["EffectiveFeature"];
export type AuditEvent = Schemas["AuditEvent"];
export type AuditVerification = Schemas["AuditVerification"];
export type OrgUnit = Schemas["OrgUnit"];
export type OrgUnitDetail = Schemas["OrgUnitDetail"];
export type OrgUnitTreeNode = Schemas["OrgUnitTreeNode"];
export type OrgUnitHistoryEntry = Schemas["OrgUnitHistoryEntry"];
export type OrgUnitSummary = Schemas["OrgUnitSummaryResponse"];
export type TenantSettings = Schemas["TenantSettings"];
export type OrgUnitType = NonNullable<Schemas["OrgUnit"]["type"]>;
