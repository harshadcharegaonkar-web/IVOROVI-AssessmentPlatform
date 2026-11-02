package com.ivorovi.platform.idempotency;

import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Claims or replays the idempotency key before an {@link Idempotent} handler runs. Completion (storing the
 * response) happens in {@link IdempotencyFilter} once the response is fully written.
 */
@Component
class IdempotencyInterceptor implements HandlerInterceptor {

    static final String HEADER = "Idempotency-Key";
    static final String CLAIM_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".claim";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    record Claim(java.util.UUID tenant, java.util.UUID user, String key) {}

    private final IdempotencyStore store;

    IdempotencyInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm) || !hm.hasMethodAnnotation(Idempotent.class)) return true;
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "This operation requires an Idempotency-Key header.");
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("idempotency_key_invalid", "Idempotency-Key must be 8-100 characters of A-Z, a-z, 0-9, '-' or '_'.");
        }
        if (!(request instanceof CachedBodyRequest body)) {
            throw new IllegalStateException("IdempotencyFilter did not wrap the request");
        }
        if (body.tooLarge()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The request body is too large.");
        }
        IvoroviPrincipal p = CurrentUser.require();
        String hash = hash(request, body.body());
        if (store.claim(p.tenantId(), p.userId(), key, request.getMethod(), request.getRequestURI(), hash)) {
            request.setAttribute(CLAIM_ATTRIBUTE, new Claim(p.tenantId(), p.userId(), key));
            return true;
        }
        Optional<IdempotencyStore.Existing> existing = store.find(p.tenantId(), p.userId(), key);
        if (existing.isEmpty()) {
            // expired between claim attempt and lookup: treat as concurrent use, the client can retry
            throw ApiException.conflict("idempotency_in_progress", "A request with this Idempotency-Key is being processed.");
        }
        IdempotencyStore.Existing e = existing.get();
        if (!e.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This Idempotency-Key was already used for a different request.");
        }
        if (!"COMPLETED".equals(e.status())) {
            throw ApiException.conflict("idempotency_in_progress", "A request with this Idempotency-Key is being processed.");
        }
        response.setStatus(e.responseStatus());
        if (e.contentType() != null) response.setContentType(e.contentType());
        response.setHeader("Idempotency-Replayed", "true");
        if (e.body() != null) response.getOutputStream().write(e.body());
        return false;
    }

    private static String hash(HttpServletRequest request, byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((request.getMethod() + ' ' + request.getRequestURI() + '?' + request.getQueryString() + '\n'
                + request.getContentType() + '\n').getBytes(StandardCharsets.UTF_8));
        md.update(body);
        return HexFormat.of().formatHex(md.digest());
    }
}
