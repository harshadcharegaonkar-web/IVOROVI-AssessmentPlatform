package com.ivorovi.features;

import com.ivorovi.features.internal.FeatureFlagOverride;
import com.ivorovi.features.internal.FeatureFlagOverrideRepository;
import com.ivorovi.features.internal.OverrideCache;
import com.ivorovi.features.internal.OverrideSnapshot;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.tenancy.InstitutionLookup;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published API for feature checks ({@link #isEnabled}) and administration of overrides. */
@Service
public class FeatureService {

    /** Admin view of one flag at one scope. */
    public record FlagConfiguration(FeatureFlag flag, boolean deploymentDisabled, Optional<Boolean> tenantOverride,
                                    Optional<Boolean> institutionOverride, FeatureState effective) {}

    private final OverrideCache cache;
    private final FeatureFlagOverrideRepository repository;
    private final FeatureProperties properties;
    private final InstitutionLookup institutions;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;

    FeatureService(OverrideCache cache, FeatureFlagOverrideRepository repository, FeatureProperties properties,
                   InstitutionLookup institutions, Authz authz, AuditLog audit, Outbox outbox) {
        this.cache = cache;
        this.repository = repository;
        this.properties = properties;
        this.institutions = institutions;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
    }

    public boolean isEnabled(FeatureFlag flag, UUID institutionIdOrNull) {
        return state(flag, institutionIdOrNull).enabled();
    }

    public FeatureState state(FeatureFlag flag, UUID institutionIdOrNull) {
        return configuration(flag, snapshot(), institutionIdOrNull).effective();
    }

    public List<FeatureState> effective(UUID institutionIdOrNull) {
        requireInstitution(institutionIdOrNull);
        OverrideSnapshot s = snapshot();
        return Arrays.stream(FeatureFlag.values()).map(f -> configuration(f, s, institutionIdOrNull).effective()).toList();
    }

    public List<FlagConfiguration> configurations(UUID institutionIdOrNull) {
        authz.require(Permission.FEATURE_READ.key(), institutionIdOrNull);
        requireInstitution(institutionIdOrNull);
        OverrideSnapshot s = snapshot();
        return Arrays.stream(FeatureFlag.values()).map(f -> configuration(f, s, institutionIdOrNull)).toList();
    }

    @Transactional
    public FlagConfiguration setOverride(FeatureFlag flag, UUID institutionIdOrNull, boolean enabled) {
        authz.require(Permission.FEATURE_MANAGE.key(), institutionIdOrNull);
        requireInstitution(institutionIdOrNull);
        if (institutionIdOrNull != null && !flag.institutionOverridable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_institution_overridable",
                    "This feature can only be configured for the whole organization.");
        }
        OverrideSnapshot before = snapshot();
        if (enabled && properties.isDeploymentDisabled(flag)) {
            throw ApiException.conflict("disabled_by_deployment", "This feature is disabled for this deployment.");
        }
        if (enabled && institutionIdOrNull != null
                && !FeatureResolver.resolve(flag, false, before.tenant(flag.key()), Optional.empty()).enabled()) {
            throw ApiException.conflict("disabled_at_organization_level",
                    "Enable this feature for the organization before enabling it for an institution.");
        }
        Optional<Boolean> previous = institutionIdOrNull == null
                ? before.tenant(flag.key()) : before.institution(flag.key(), institutionIdOrNull);

        FeatureFlagOverride override = repository.findOverride(flag.key(), institutionIdOrNull)
                .orElseGet(() -> new FeatureFlagOverride(institutionIdOrNull, flag.key()));
        override.set(enabled, CurrentUser.require().userId());
        repository.saveAndFlush(override);
        changed(flag, institutionIdOrNull, previous, Optional.of(enabled));
        return configuration(flag, snapshotAfterChange(), institutionIdOrNull);
    }

    @Transactional
    public FlagConfiguration clearOverride(FeatureFlag flag, UUID institutionIdOrNull) {
        authz.require(Permission.FEATURE_MANAGE.key(), institutionIdOrNull);
        requireInstitution(institutionIdOrNull);
        Optional<FeatureFlagOverride> existing = repository.findOverride(flag.key(), institutionIdOrNull);
        if (existing.isPresent()) {
            repository.delete(existing.get());
            repository.flush();
            changed(flag, institutionIdOrNull, Optional.of(existing.get().isEnabled()), Optional.empty());
        }
        return configuration(flag, snapshotAfterChange(), institutionIdOrNull);
    }

    private void changed(FeatureFlag flag, UUID institutionId, Optional<Boolean> previous, Optional<Boolean> next) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("flag", flag.key());
        details.put("scope", institutionId == null ? "TENANT" : "INSTITUTION");
        if (institutionId != null) details.put("institutionId", institutionId.toString());
        details.put("previous", previous.map(String::valueOf).orElse("inherit"));
        details.put("new", next.map(String::valueOf).orElse("inherit"));
        audit.recordSuccess("feature.override.changed", "feature_flag", flag.key(), details);
        outbox.append("feature_flag", flag.key(), "feature.override.changed", details);
        cache.evict(TenantContext.requireTenantId());
    }

    private OverrideSnapshot snapshot() {
        return cache.load(TenantContext.requireTenantId());
    }

    /** Within the writing transaction, read the uncommitted state directly (the cache is evicted on commit). */
    private OverrideSnapshot snapshotAfterChange() {
        return new OverrideSnapshot(repository.findAll().stream()
                .map(o -> new OverrideSnapshot.Entry(o.getFlagKey(), o.getInstitutionId(), o.isEnabled())).toList());
    }

    private FlagConfiguration configuration(FeatureFlag flag, OverrideSnapshot s, UUID institutionId) {
        boolean deployment = properties.isDeploymentDisabled(flag);
        Optional<Boolean> tenant = s.tenant(flag.key());
        Optional<Boolean> inst = s.institution(flag.key(), institutionId);
        return new FlagConfiguration(flag, deployment, tenant, inst, FeatureResolver.resolve(flag, deployment, tenant, inst));
    }

    private void requireInstitution(UUID institutionId) {
        if (institutionId != null && institutions.find(institutionId).isEmpty()) {
            throw ApiException.notFound("Institution");
        }
    }
}
