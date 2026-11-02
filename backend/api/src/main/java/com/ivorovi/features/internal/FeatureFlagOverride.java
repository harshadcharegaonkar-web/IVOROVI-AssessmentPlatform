package com.ivorovi.features.internal;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.persistence.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feature_flag_override")
public class FeatureFlagOverride extends TenantOwnedEntity {

    @Id
    private UUID id;

    @Column(name = "org_unit_id", updatable = false)
    private UUID orgUnitId;

    @Column(name = "flag_key", nullable = false, updatable = false, length = 100)
    private String flagKey;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private long version;

    protected FeatureFlagOverride() {}

    public FeatureFlagOverride(UUID orgUnitId, String flagKey) {
        this.id = Ids.newId();
        this.orgUnitId = orgUnitId;
        this.flagKey = flagKey;
    }

    public void set(boolean value, UUID by) {
        this.enabled = value;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrgUnitId() { return orgUnitId; }
    public String getFlagKey() { return flagKey; }
    public boolean isEnabled() { return enabled; }
}
