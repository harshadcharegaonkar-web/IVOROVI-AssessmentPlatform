package com.ivorovi.platform.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Code-defined permission catalog. Roles (system and custom) are bundles of these keys. A permission is
 * granted at a scope; an assignment at TENANT scope covers every institution in the tenant.
 * New modules add entries here together with the migration that grants them to system roles.
 */
public enum Permission {
    PLATFORM_TENANT_MANAGE("platform.tenant.manage", "Create, suspend and configure tenants"),
    INSTITUTION_READ("institution.read", "View institutions"),
    INSTITUTION_MANAGE("institution.manage", "Create and modify institutions"),
    USER_READ("user.read", "View user accounts"),
    USER_MANAGE("user.manage", "Invite, modify and deactivate user accounts"),
    ROLE_MANAGE("role.manage", "Create custom roles and assign roles"),
    FEATURE_READ("feature.read", "View feature configuration"),
    FEATURE_MANAGE("feature.manage", "Enable or disable features"),
    AUDIT_READ("audit.read", "View and verify the audit trail");

    private final String key;
    private final String description;

    Permission(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String key() { return key; }

    public String description() { return description; }

    public static Optional<Permission> fromKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
