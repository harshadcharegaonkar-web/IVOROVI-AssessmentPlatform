package com.ivorovi.platform.idempotency;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * For requests that carry an Idempotency-Key: buffers the request body (for hashing) and the response (for
 * storage). After the handler, a claimed key is completed with the response, or released on a server error so
 * the client can retry.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyStore store;

    IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(IdempotencyInterceptor.HEADER) == null || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest wrappedRequest = new CachedBodyRequest(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            Object attr = wrappedRequest.getAttribute(IdempotencyInterceptor.CLAIM_ATTRIBUTE);
            if (attr instanceof IdempotencyInterceptor.Claim c) {
                int status = wrappedResponse.getStatus();
                TenantContext.runInTenant(c.tenant(), () -> {
                    if (status >= 500) {
                        store.release(c.tenant(), c.user(), c.key());
                    } else {
                        store.complete(c.tenant(), c.user(), c.key(), status, wrappedResponse.getContentType(),
                                wrappedResponse.getContentAsByteArray());
                    }
                });
            }
            wrappedResponse.copyBodyToResponse();
        }
    }
}
