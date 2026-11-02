package com.ivorovi.platform.persistence;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.UUID;

/**
 * Base for tenant-owned JPA entities. {@code tenant_id} is assigned from the bound tenant context on insert and
 * may never change; Row Level Security independently rejects writes for any other tenant.
 */
@MappedSuperclass
public abstract class TenantOwnedEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void assignTenant() {
        UUID current = TenantContext.requireTenantId();
        if (tenantId == null) {
            tenantId = current;
        } else if (!tenantId.equals(current)) {
            throw new IllegalStateException("Entity tenant does not match tenant context");
        }
    }

    @PreUpdate
    void checkTenant() {
        if (!TenantContext.requireTenantId().equals(tenantId)) {
            throw new IllegalStateException("Entity tenant does not match tenant context");
        }
    }

    public UUID getTenantId() { return tenantId; }
}
