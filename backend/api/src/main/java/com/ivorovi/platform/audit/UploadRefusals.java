package com.ivorovi.platform.audit;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records refused file uploads (KI-53). A refusal is written in its <b>own transaction</b>, because the request's
 * transaction is rolled back when the upload is refused.
 * <ul>
 *   <li>With a tenant context (authenticated caller) the refusal goes to the hash-chained audit trail
 *       ({@code upload.refused}, or the caller's more specific action) with actor, tenant, timestamp and correlation ID
 *       added by {@link AuditLog}.</li>
 *   <li>Without one (no or invalid token) there is no tenant to attribute it to: it goes to the platform
 *       {@code security_event} log with method, path, status, reason, client address and correlation ID.</li>
 * </ul>
 * Never records file contents, file names or personal values; callers pass identifiers and codes only. A request is
 * recorded at most once ({@link #AUDITED} marks it).
 */
@Component
public class UploadRefusals {

    public static final String AUDITED = "ivorovi.upload-refusal.audited";
    private static final Logger log = LoggerFactory.getLogger(UploadRefusals.class);

    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate separate;

    UploadRefusals(AuditLog audit, JdbcClient jdbc, JsonMapper json, PlatformTransactionManager txManager) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.separate = new TransactionTemplate(txManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A multipart POST: what this class treats as an upload. */
    public static boolean isUpload(HttpServletRequest request) {
        if (request == null || !"POST".equalsIgnoreCase(request.getMethod())) return false;
        String type = request.getContentType();
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    /**
     * Records a refusal decided by a service, with domain details (institution, student, category, reason).
     * {@code action} is the domain action, e.g. {@code student.document.upload_refused}.
     */
    public void refusedByService(HttpServletRequest request, String action, String resourceType, String resourceId, int status, String reason,
                                 Map<String, String> details) {
        Map<String, String> d = new LinkedHashMap<>(details);
        d.put("reason", reason);
        d.put("status", String.valueOf(status));
        if (request != null) {
            d.put("method", request.getMethod());
            d.put("path", request.getRequestURI());
            if (request.getContentLengthLong() >= 0) d.put("contentLength", String.valueOf(request.getContentLengthLong()));
        }
        write(request, action, resourceType, resourceId, status, reason, d);
    }

    /** Records a refusal decided by the framework (authentication, permission, size limit, malformed request). */
    public void refusedByFramework(HttpServletRequest request, int status, String reason) {
        if (request == null || Boolean.TRUE.equals(request.getAttribute(AUDITED))) return;
        Map<String, String> d = new LinkedHashMap<>();
        d.put("reason", reason);
        d.put("status", String.valueOf(status));
        d.put("method", request.getMethod());
        d.put("path", request.getRequestURI());
        if (request.getContentLengthLong() >= 0) d.put("contentLength", String.valueOf(request.getContentLengthLong()));
        write(request, "upload.refused", "upload", request.getRequestURI(), status, reason, d);
    }

    private void write(HttpServletRequest request, String action, String resourceType, String resourceId, int status, String reason,
                       Map<String, String> details) {
        if (request != null) request.setAttribute(AUDITED, Boolean.TRUE);
        AuditOutcome outcome = status == 401 || status == 403 ? AuditOutcome.DENIED : AuditOutcome.FAILURE;
        try {
            if (TenantContext.currentTenantId().isPresent()) {
                separate.executeWithoutResult(s -> audit.record(action, resourceType, resourceId, outcome, details));
            } else {
                TenantContext.callAsPlatform(() -> {
                    separate.executeWithoutResult(s -> jdbc.sql("""
                            INSERT INTO security_event (id, category, method, path, status, reason, client_ip, user_agent, trace_id, details)
                            VALUES (?, 'upload.refused', ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""")
                            .params(Ids.newId(), request == null ? null : request.getMethod(), request == null ? null : truncate(request.getRequestURI(), 500),
                                    status, reason, request == null ? null : request.getRemoteAddr(),
                                    request == null ? null : truncate(request.getHeader("User-Agent"), 300), MDC.get(RequestCorrelationFilter.MDC_TRACE_ID),
                                    json.writeValueAsString(details)).update());
                    return null;
                });
            }
        } catch (RuntimeException e) {
            // best effort: never mask the original refusal
            log.error("Failed to record refused upload ({}): {}", reason, e.getClass().getName());
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
