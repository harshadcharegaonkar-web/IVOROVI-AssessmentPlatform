package com.ivorovi.platformadmin;

import com.ivorovi.platformadmin.internal.SecurityPolicyService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Module API (approved contract, demo programme Day 1): organization policies other modules may read. No authorization
 * here; callers must not expose the result beyond what their own endpoint authorizes.
 */
@Component
public class PlatformPolicies {

    /** The organization's staff session policy; {@code idleMinutes} / {@code absoluteHours} null mean no limit. */
    public record SecurityPolicy(boolean mfaRequired, Integer idleMinutes, Integer absoluteHours) {
        public static final SecurityPolicy NONE = new SecurityPolicy(false, null, null);
    }

    private final SecurityPolicyService policies;

    PlatformPolicies(SecurityPolicyService policies) {
        this.policies = policies;
    }

    public SecurityPolicy securityPolicy(UUID tenantId) {
        return policies.effective(tenantId);
    }
}
