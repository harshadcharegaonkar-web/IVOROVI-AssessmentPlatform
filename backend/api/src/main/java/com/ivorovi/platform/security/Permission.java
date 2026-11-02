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
    ORG_UNIT_READ("org_unit.read", "View the organization hierarchy (universities, colleges, campuses, departments)"),
    ORG_UNIT_MANAGE("org_unit.manage", "Create, edit, move, deactivate and delete organization units"),
    TENANT_SETTINGS_MANAGE("tenant.settings.manage", "Change organization settings such as terminology"),
    USER_READ("user.read", "View user accounts"),
    USER_MANAGE("user.manage", "Invite, modify and deactivate user accounts"),
    ROLE_MANAGE("role.manage", "Create and edit custom roles"),
    ROLE_READ("role.read", "View roles and role assignments"),
    ROLE_ASSIGN("role.assign", "Assign and revoke roles within your own permissions and scope"),
    STAFF_READ("staff.read", "View staff profiles"),
    STAFF_MANAGE("staff.manage", "Create and edit staff profiles and their organizational assignments"),
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
