package com.ivorovi.platform.security;

import com.ivorovi.platform.config.DeliveryProperties;
import com.ivorovi.platform.ratelimit.RateLimitProperties;
import com.ivorovi.platform.ratelimit.RateLimiter;
import com.ivorovi.platform.tenancy.PortalDirectory;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import com.ivorovi.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filters of the delivery chain. Not Spring beans, so they are never registered a second time as servlet filters. */
public final class DeliveryFilters {

    public static final String PORTALS = "/api/v1/delivery/portals/";

    private DeliveryFilters() {}

    /** Portal key in the path → organization. Unknown or disabled portals (and suspended organizations) are a plain 404. */
    public static String portalKey(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(PORTALS)) return null;
        String rest = path.substring(PORTALS.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    public static final class PortalTenant extends OncePerRequestFilter {
        private final PortalDirectory portals;
        private final TenantDirectory tenants;
        private final ProblemResponses problems;

        PortalTenant(PortalDirectory portals, TenantDirectory tenants, ProblemResponses problems) {
            this.portals = portals;
            this.tenants = tenants;
            this.problems = problems;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String key = portalKey(request);
            if (key == null || "OPTIONS".equals(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            Optional<UUID> tenant = portals.tenantOf(key).filter(t -> tenants.find(t).map(i -> i.status().allowsAccess()).orElse(false));
            if (tenant.isEmpty()) {
                problems.write(response, HttpStatus.NOT_FOUND, "portal_not_found", "This exam portal does not exist or is not available.");
                return;
            }
            TenantContext.bindRequestTenant(tenant.get());
            MDC.put("tenantId", tenant.get().toString());
            chain.doFilter(request, response);
        }
    }

    /** Per-candidate and per-organization ceilings for authenticated delivery calls; per-IP limit for the public ones. */
    public static final class Limits extends OncePerRequestFilter {
        private final RateLimiter limiter;
        private final RateLimitProperties rateLimits;
        private final DeliveryProperties props;
        private final ProblemResponses problems;

        Limits(RateLimiter limiter, RateLimitProperties rateLimits, DeliveryProperties props, ProblemResponses problems) {
            this.limiter = limiter;
            this.rateLimits = rateLimits;
            this.props = props;
            this.problems = problems;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!rateLimits.enabled() || "OPTIONS".equals(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            Optional<CandidateAuthentication> candidate = CandidateAuthentication.current();
            RateLimiter.Decision d;
            if (candidate.isEmpty()) {
                d = limiter.acquire("delivery-public:" + request.getRemoteAddr(), Math.max(rateLimits.publicPerIpPerMinute(), 60), true);
            } else {
                UUID tenant = TenantContext.currentTenantId().orElse(null);
                d = tenant == null ? null : limiter.acquire("delivery-tenant:" + tenant, props.tenantPerMinute(), true);
                if (d == null || d.allowed()) d = limiter.acquire("candidate:" + candidate.get().subject(), props.candidatePerMinute(), true);
            }
            if (!d.allowed()) {
                response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests. Retry after " + d.retryAfterSeconds() + " seconds.");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
