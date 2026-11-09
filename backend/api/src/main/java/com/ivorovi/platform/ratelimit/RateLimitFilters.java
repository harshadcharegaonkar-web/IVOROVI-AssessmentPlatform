package com.ivorovi.platform.ratelimit;

import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two filters placed around bearer-token authentication in the security chain (see SecurityConfiguration).
 * Not Spring beans, so they are never registered a second time as plain servlet filters.
 */
public final class RateLimitFilters {

    private RateLimitFilters() {}

    private static boolean applies(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    private static void reject(HttpServletResponse response, ProblemResponses problems, RateLimiter.Decision d)
            throws IOException {
        response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
        headers(response, d);
        problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Retry after " + d.retryAfterSeconds() + " seconds.");
    }

    private static void headers(HttpServletResponse response, RateLimiter.Decision d) {
        response.setHeader("RateLimit-Limit", String.valueOf(d.limit()));
        response.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
        response.setHeader("RateLimit-Reset", String.valueOf(d.resetSeconds()));
    }

    /**
     * Before authentication: blocks an IP that produced too many failed authentications; counts new failures. With
     * {@code refuse=false} (exam delivery, KI-66) failures are only counted and reported: candidate tokens are signed
     * and cannot be guessed, and a pre-authentication address block would let anyone on a campus network lock out every
     * candidate behind the same NAT address.
     */
    public static final class FailedAuthentication extends OncePerRequestFilter {
        private final RateLimiter limiter;
        private final RateLimitProperties props;
        private final ProblemResponses problems;
        private final boolean refuse;

        public FailedAuthentication(RateLimiter limiter, RateLimitProperties props, ProblemResponses problems) {
            this(limiter, props, problems, true);
        }

        public FailedAuthentication(RateLimiter limiter, RateLimitProperties props, ProblemResponses problems, boolean refuse) {
            this.limiter = limiter;
            this.props = props;
            this.problems = problems;
            this.refuse = refuse;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!props.enabled() || !applies(request)) {
                chain.doFilter(request, response);
                return;
            }
            String key = (refuse ? "authfail:" : "authfail-report:") + request.getRemoteAddr();
            RateLimiter.Decision check = limiter.acquire(key, props.failedAuthPerIpPerMinute(), false);
            if (refuse && !check.allowed()) {
                reject(response, problems, check);
                return;
            }
            chain.doFilter(request, response);
            if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
                limiter.acquire(key, props.failedAuthPerIpPerMinute(), true);
            }
        }
    }

    /** Unauthenticated public endpoints ({@code /api/v1/public/**}): every request counts against the caller's IP. */
    public static final class PublicEndpoints extends OncePerRequestFilter {
        private final RateLimiter limiter;
        private final RateLimitProperties props;
        private final ProblemResponses problems;

        public PublicEndpoints(RateLimiter limiter, RateLimitProperties props, ProblemResponses problems) {
            this.limiter = limiter;
            this.props = props;
            this.problems = problems;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/v1/public/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (props.enabled()) {
                RateLimiter.Decision d = limiter.acquire("public:" + request.getRemoteAddr(), props.publicPerIpPerMinute(), true);
                if (!d.allowed()) {
                    reject(response, problems, d);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    /** After authentication: per-user (read/write classes) and per-tenant ceilings. */
    public static final class Authenticated extends OncePerRequestFilter {
        private final RateLimiter limiter;
        private final RateLimitProperties props;
        private final ProblemResponses problems;

        public Authenticated(RateLimiter limiter, RateLimitProperties props, ProblemResponses problems) {
            this.limiter = limiter;
            this.props = props;
            this.problems = problems;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Optional<IvoroviPrincipal> user = CurrentUser.get();
            if (!props.enabled() || !applies(request) || user.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            IvoroviPrincipal p = user.get();
            boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
            RateLimiter.Decision tenant = limiter.acquire("tenant:" + p.tenantId(), props.tenantPerMinute(), true);
            if (!tenant.allowed()) {
                reject(response, problems, tenant);
                return;
            }
            RateLimiter.Decision mine = read
                    ? limiter.acquire("user-read:" + p.tenantId() + ":" + p.userId(), props.userReadPerMinute(), true)
                    : limiter.acquire("user-write:" + p.tenantId() + ":" + p.userId(), props.userWritePerMinute(), true);
            if (!mine.allowed()) {
                reject(response, problems, mine);
                return;
            }
            headers(response, mine);
            chain.doFilter(request, response);
        }
    }
}
