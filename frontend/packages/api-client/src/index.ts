export { createApiClient, unwrap, type ApiClientOptions, type IvoroviApi } from "./client";
export { ApiError, type Problem } from "./errors";
export type { components, paths } from "./generated/schema";

import type { components } from "./generated/schema";
type Schemas = components["schemas"];
export type MeResponse = Schemas["MeResponse"];
export type InstitutionView = Schemas["InstitutionView"];
export type FeatureConfigurationView = Schemas["FeatureConfigurationView"];
export type EffectiveFeature = Schemas["EffectiveFeature"];
export type AuditEvent = Schemas["AuditEvent"];
export type AuditVerification = Schemas["AuditVerification"];
