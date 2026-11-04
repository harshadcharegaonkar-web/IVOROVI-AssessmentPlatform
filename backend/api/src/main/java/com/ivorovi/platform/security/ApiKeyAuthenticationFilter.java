package com.ivorovi.platform.security;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates API clients by key. API clients are read-only: only GET and HEAD are accepted; a key together with a
 * bearer token is rejected. Without a key the request continues to bearer-token authentication unchanged.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticator authenticator;
    private final ProblemResponses problems;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, ProblemResponses problems) {
        this.authenticator = authenticator;
        this.problems = problems;
    }

    static String presentedKey(HttpServletRequest request) {
        String header = request.getHeader(ApiKeyAuthenticator.HEADER);
        if (header != null && !header.isBlank()) return header.trim();
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "ApiKey ", 0, 7)) return auth.substring(7).trim();
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/api/v1/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String key = presentedKey(request);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        if (request.getHeader(ApiKeyAuthenticator.HEADER) != null && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            problems.write(response, HttpStatus.BAD_REQUEST, "ambiguous_credentials", "Send either an API key or an access token, not both.");
            return;
        }
        ApiKeyAuthenticator.Result result = authenticator.authenticate(key, request.getRemoteAddr());
        if (result instanceof ApiKeyAuthenticator.Rejected r) {
            HttpStatus status = HttpStatus.valueOf(r.status());
            if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "ApiKey");
            problems.write(response, status, r.reason(), switch (r.reason()) {
                case "api_key_ip_not_allowed" -> "This API client may not be used from this network address.";
                case "api_key_expired" -> "This API key has expired.";
                case "api_key_revoked" -> "This API key or client has been revoked.";
                default -> "A valid API key is required.";
            });
            return;
        }
        ApiKeyAuthenticator.Authenticated a = (ApiKeyAuthenticator.Authenticated) result;
        IvoroviPrincipal principal = a.principal();
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (!read) {
            authenticator.recordUsage(principal.tenantId(), a.clientId(), true);
            problems.write(response, HttpStatus.FORBIDDEN, "api_key_read_only", "API clients have read-only access.");
            return;
        }
        TenantContext.bindRequestTenant(principal.tenantId());
        MDC.put("tenantId", principal.tenantId().toString());
        MDC.put("userId", principal.userId().toString());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new IvoroviAuthentication(principal, null));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            int s = response.getStatus();
            authenticator.recordUsage(principal.tenantId(), a.clientId(), s == 401 || s == 403 || s == 429);
        }
    }
}
