package com.ivorovi.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves tenants. Runs in platform context because it executes before a tenant context exists. */
@Component
public class TenantDirectory {

    public enum Status { TRIAL, ACTIVE, SUSPENDED, TERMINATED;
        public boolean allowsAccess() { return this == TRIAL || this == ACTIVE; }
    }

    public record TenantInfo(UUID id, String code, String name, Status status, String brandingJson) {}

    private final JdbcClient jdbc;

    TenantDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantInfo> find(UUID tenantId) {
        return TenantContext.callAsPlatform(() -> jdbc
                .sql("SELECT id, code, name, status, branding::text AS branding FROM tenant WHERE id = ?")
                .param(tenantId)
                .query((rs, n) -> new TenantInfo(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), Status.valueOf(rs.getString("status")), rs.getString("branding")))
                .optional());
    }
}
