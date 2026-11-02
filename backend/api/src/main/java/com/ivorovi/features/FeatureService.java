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
import com.ivorovi.tenancy.OrgUnitLookup;
import com.ivorovi.tenancy.OrgUnitLookup.OrgUnitRef;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published API for feature checks ({@link #isEnabled}) and administration of overrides.
 *
 * <p>Overrides exist at tenant level and on institutions (universities and colleges). A unit's effective value
 * is resolved down its institution chain (university → college); campuses and departments inherit from the
 * institutions above them.
 */
@Service
public class FeatureService {

    /** Admin view of one flag at one scope; {@code unitOverride} is the override on that unit itself. */
    public record FlagConfiguration(FeatureFlag flag, boolean deploymentDisabled, Optional<Boolean> tenantOverride,
                                    Optional<Boolean> unitOverride, FeatureState inherited, FeatureState effective) {}

    private final OverrideCache cache;
    private final FeatureFlagOverrideRepository repository;
    private final FeatureProperties properties;
    private final OrgUnitLookup units;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;

    FeatureService(OverrideCache cache, FeatureFlagOverrideRepository repository, FeatureProperties properties,
                   OrgUnitLookup units, Authz authz, AuditLog audit, Outbox outbox) {
        this.cache = cache;
        this.repository = repository;
        this.properties = properties;
        this.units = units;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
    }

    public boolean isEnabled(FeatureFlag flag, UUID orgUnitIdOrNull) {
        return state(flag, orgUnitIdOrNull).enabled();
    }

    public FeatureState state(FeatureFlag flag, UUID orgUnitIdOrNull) {
        return configuration(flag, snapshot(), chain(orgUnitIdOrNull)).effective();
    }

    /** Effective features for the tenant or for any unit (campuses/departments inherit from their institutions). */
    public List<FeatureState> effective(UUID orgUnitIdOrNull) {
        List<OrgUnitRef> chain = chain(orgUnitIdOrNull);
        OverrideSnapshot s = snapshot();
        return Arrays.stream(FeatureFlag.values()).map(f -> configuration(f, s, chain).effective()).toList();
    }

    /** Configuration view for the organization or one institution (university/college). */
    public List<FlagConfiguration> configurations(UUID orgUnitIdOrNull) {
        authz.require(Permission.FEATURE_READ.key(), orgUnitIdOrNull);
        List<OrgUnitRef> chain = institutionChain(orgUnitIdOrNull);
        OverrideSnapshot s = snapshot();
        return Arrays.stream(FeatureFlag.values()).map(f -> configuration(f, s, chain)).toList();
    }

    @Transactional
    public FlagConfiguration setOverride(FeatureFlag flag, UUID orgUnitIdOrNull, boolean enabled) {
        authz.require(Permission.FEATURE_MANAGE.key(), orgUnitIdOrNull);
        List<OrgUnitRef> chain = institutionChain(orgUnitIdOrNull);
        if (orgUnitIdOrNull != null && !flag.institutionOverridable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_institution_overridable",
                    "This feature can only be configured for the whole organization.");
        }
        OverrideSnapshot before = snapshot();
        if (enabled && properties.isDeploymentDisabled(flag)) {
            throw ApiException.conflict("disabled_by_deployment", "This feature is disabled for this deployment.");
        }
        if (enabled && orgUnitIdOrNull != null) {
            FeatureState above = configuration(flag, before, chain.subList(0, chain.size() - 1)).effective();
            if (!above.enabled()) {
                throw above.source() == FeatureState.Source.INSTITUTION
                        ? ApiException.conflict("disabled_at_parent_level",
                                "This feature is switched off for a parent institution. Enable it there first.")
                        : ApiException.conflict("disabled_at_organization_level",
                                "Enable this feature for the organization before enabling it for an institution.");
            }
        }
        Optional<Boolean> previous = orgUnitIdOrNull == null ? before.tenant(flag.key()) : before.unit(flag.key(), orgUnitIdOrNull);
        FeatureFlagOverride override = repository.findOverride(flag.key(), orgUnitIdOrNull)
                .orElseGet(() -> new FeatureFlagOverride(orgUnitIdOrNull, flag.key()));
        override.set(enabled, CurrentUser.require().userId());
        repository.saveAndFlush(override);
        changed(flag, orgUnitIdOrNull, previous, Optional.of(enabled));
        return configuration(flag, snapshotAfterChange(), chain);
    }

    @Transactional
    public FlagConfiguration clearOverride(FeatureFlag flag, UUID orgUnitIdOrNull) {
        authz.require(Permission.FEATURE_MANAGE.key(), orgUnitIdOrNull);
        List<OrgUnitRef> chain = institutionChain(orgUnitIdOrNull);
        Optional<FeatureFlagOverride> existing = repository.findOverride(flag.key(), orgUnitIdOrNull);
        if (existing.isPresent()) {
            repository.delete(existing.get());
            repository.flush();
            changed(flag, orgUnitIdOrNull, Optional.of(existing.get().isEnabled()), Optional.empty());
        }
        return configuration(flag, snapshotAfterChange(), chain);
    }

    private void changed(FeatureFlag flag, UUID orgUnitId, Optional<Boolean> previous, Optional<Boolean> next) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("flag", flag.key());
        details.put("scope", orgUnitId == null ? "TENANT" : "INSTITUTION");
        if (orgUnitId != null) details.put("orgUnitId", orgUnitId.toString());
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
                .map(o -> new OverrideSnapshot.Entry(o.getFlagKey(), o.getOrgUnitId(), o.isEnabled())).toList());
    }

    /** Institutions above and including the unit (top-down). Empty for the organization level. */
    private List<OrgUnitRef> chain(UUID orgUnitId) {
        if (orgUnitId == null) return List.of();
        List<OrgUnitRef> all = units.ancestorsOrSelf(orgUnitId);
        if (all.isEmpty()) throw ApiException.notFound("Organization unit");
        return all.stream().filter(u -> u.type().isInstitution()).toList();
    }

    /** Like {@link #chain}, but the unit itself must be an institution (overrides live only there). */
    private List<OrgUnitRef> institutionChain(UUID orgUnitId) {
        if (orgUnitId == null) return List.of();
        List<OrgUnitRef> all = units.ancestorsOrSelf(orgUnitId);
        if (all.isEmpty()) throw ApiException.notFound("Organization unit");
        if (!all.getLast().type().isInstitution()) {
            throw ApiException.badRequest("feature_scope_not_supported",
                    "Features are configured on universities and colleges; campuses and departments inherit them.");
        }
        return all.stream().filter(u -> u.type().isInstitution()).toList();
    }

    private FlagConfiguration configuration(FeatureFlag flag, OverrideSnapshot s, List<OrgUnitRef> chain) {
        boolean deployment = properties.isDeploymentDisabled(flag);
        Optional<Boolean> tenant = s.tenant(flag.key());
        List<Optional<Boolean>> overrides = chain.stream().map(u -> s.unit(flag.key(), u.id())).toList();
        FeatureState effective = FeatureResolver.resolve(flag, deployment, tenant, overrides);
        FeatureState inherited = FeatureResolver.resolve(flag, deployment, tenant,
                overrides.isEmpty() ? List.of() : overrides.subList(0, overrides.size() - 1));
        Optional<Boolean> own = chain.isEmpty() ? Optional.empty() : overrides.getLast();
        return new FlagConfiguration(flag, deployment, tenant, own, inherited, effective);
    }
}
