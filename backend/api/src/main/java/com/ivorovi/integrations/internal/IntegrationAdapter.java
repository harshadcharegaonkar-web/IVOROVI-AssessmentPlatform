package com.ivorovi.integrations.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Service-provider interface of the integration framework. Adapters are vendor-neutral, configured with plain fields
 * plus at most one secret (stored encrypted), and must never log or return the secret. Vendor-specific connectors
 * (a particular SIS, ERP or LMS) are out of scope until approved; they will implement this interface.
 */
public interface IntegrationAdapter {

    enum Kind { SIS_ERP, LMS, EMAIL, APPLICATION }

    @Schema(name = "IntegrationConfigField")
    record Field(String key, String label, String type, boolean required, List<String> options, String help) {}

    @Schema(name = "IntegrationAdapterInfo")
    record Info(String key, String label, String description, List<Kind> kinds, List<Field> fields, String secretLabel, boolean secretRequired,
                boolean supportsSync, String syncDescription) {}

    /** Outcome of a connection test or sync attempt. {@code message} is shown to administrators (no secrets). */
    record Outcome(boolean ok, String message) {}

    Info info();

    /** Field-level problems of a configuration (empty = valid). Called before saving. */
    List<String> validate(Map<String, String> config, boolean secretPresent);

    Outcome test(Map<String, String> config, String secret);

    default Outcome sync(Map<String, String> config, String secret) {
        return new Outcome(false, "This adapter does not support synchronization.");
    }
}
