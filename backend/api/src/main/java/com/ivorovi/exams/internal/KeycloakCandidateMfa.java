package com.ivorovi.exams.internal;

import com.ivorovi.exams.CandidateMfa;
import com.ivorovi.exams.ExamsProperties;
import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Candidate-realm admin API through {@code ivorovi-candidate-provisioner} (service account with view/manage/query-users of
 * the candidate realm only; it has no access to the staff realm). An enrolled second factor is cached for 60 s.
 */
@Component
class KeycloakCandidateMfa implements CandidateMfa {

    private static final Set<String> SECOND_FACTORS = Set.of("otp", "recovery-authn-codes");
    private static final Duration TTL = Duration.ofSeconds(60);
    record Cached(boolean enrolled) {}

    private final RestClient http;
    private final ExamsProperties.Idp cfg;
    private final ValkeyJsonCache cache;
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    KeycloakCandidateMfa(ExamsProperties props, ValkeyJsonCache cache) {
        this.cfg = props.idp();
        this.cache = cache;
        var timeouts = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(3));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(cfg.adminUrl()).requestFactory(timeouts).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean enrolled(String subject) {
        String key = "ivorovi:candidate-mfa:" + sha256(subject);
        var hit = cache.get("candidate-mfa", key, Cached.class);
        if (hit.isPresent()) return hit.get().enrolled();
        List<Map<String, Object>> creds;
        try {
            creds = http.get().uri("/admin/realms/{realm}/users/{id}/credentials", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class);
        } catch (RestClientException e) {
            // fail closed: an unknown second-factor state never satisfies a REQUIRED policy
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity_provider_unavailable", "Sign-in service unavailable. Try again shortly.");
        }
        boolean enrolled = creds != null && creds.stream().anyMatch(c -> "otp".equals(c.get("type")));
        // only "enrolled" is cached: a candidate who just set up an authenticator must not wait for a stale "no"
        if (enrolled) cache.put("candidate-mfa", key, new Cached(true), TTL);
        return enrolled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reset(String subject) {
        try {
            List<Map<String, Object>> creds = http.get().uri("/admin/realms/{realm}/users/{id}/credentials", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class);
            for (Map<String, Object> c : creds == null ? List.<Map<String, Object>>of() : creds) {
                if (SECOND_FACTORS.contains(String.valueOf(c.get("type")))) {
                    http.delete().uri("/admin/realms/{realm}/users/{id}/credentials/{cid}", cfg.realm(), subject, c.get("id"))
                            .headers(h -> h.setBearerAuth(accessToken())).retrieve().toBodilessEntity();
                }
            }
            http.post().uri("/admin/realms/{realm}/users/{id}/logout", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity_provider_unavailable", "Sign-in service unavailable. Try again shortly.");
        }
        cache.delete("ivorovi:candidate-mfa:" + sha256(subject));
    }

    /**
     * Reads the realm event log (newest first): a reset is an UPDATE_PASSWORD preceded by SEND_RESET_PASSWORD for the same
     * user; it signed out other sessions when a LOGOUT for that user lies between the two.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<PasswordReset> passwordResetsSince(Instant since) {
        List<Map<String, Object>> events;
        try {
            events = http.get().uri("/admin/realms/{realm}/events?type=SEND_RESET_PASSWORD&type=UPDATE_PASSWORD&type=LOGOUT&dateFrom={from}&max=1000",
                            cfg.realm(), since.minus(Duration.ofHours(1)).toEpochMilli())
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity_provider_unavailable", "Sign-in service unavailable.");
        }
        List<Map<String, Object>> asc = new java.util.ArrayList<>(events == null ? List.of() : events);
        asc.sort(java.util.Comparator.comparingLong(e -> ((Number) e.get("time")).longValue()));
        Map<String, Long> resetRequested = new java.util.HashMap<>();
        Map<String, Long> loggedOut = new java.util.HashMap<>();
        List<PasswordReset> out = new java.util.ArrayList<>();
        for (Map<String, Object> e : asc) {
            String user = String.valueOf(e.get("userId"));
            long t = ((Number) e.get("time")).longValue();
            switch (String.valueOf(e.get("type"))) {
                case "SEND_RESET_PASSWORD" -> { resetRequested.put(user, t); loggedOut.remove(user); }
                case "LOGOUT" -> { if (resetRequested.containsKey(user)) loggedOut.put(user, t); }
                case "UPDATE_PASSWORD" -> {
                    Long req = resetRequested.remove(user);
                    if (req != null && t - req < Duration.ofHours(1).toMillis() && t > since.toEpochMilli()) {
                        out.add(new PasswordReset(user, Instant.ofEpochMilli(t), loggedOut.remove(user) != null, resetSessionOf(e)));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /**
     * The user session the reset flow continues into (pure; unit-tested). Keycloak records UPDATE_PASSWORD inside the
     * authentication flow, before the user session exists, so the event's sessionId is empty; the flow's code_id is the
     * root authentication session id, which Keycloak reuses as the id of the user session it then creates (the LOGIN that
     * follows carries it as its sessionId). Null when neither is present: the guard then ends every pre-reset session.
     */
    @SuppressWarnings("unchecked")
    static String resetSessionOf(Map<String, Object> event) {
        Object sid = event.get("sessionId");
        if (sid != null && !String.valueOf(sid).isBlank()) return String.valueOf(sid);
        Object code = event.get("details") instanceof Map<?, ?> d ? ((Map<String, Object>) d).get("code_id") : null;
        return code == null || String.valueOf(code).isBlank() ? null : String.valueOf(code);
    }

    @Override
    public void signOutEverywhere(String subject) {
        try {
            http.post().uri("/admin/realms/{realm}/users/{id}/logout", cfg.realm(), subject).headers(h -> h.setBearerAuth(accessToken())).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity_provider_unavailable", "Sign-in service unavailable.");
        }
    }

    /** Sessions to end after a reset: started before the reset and not the reset flow's own session (pure; unit-tested). */
    static List<String> sessionsToEnd(List<Map<String, Object>> sessions, Instant resetAt, String keepSessionId) {
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> s : sessions == null ? List.<Map<String, Object>>of() : sessions) {
            String id = String.valueOf(s.get("id"));
            if (id.equals(keepSessionId)) continue;
            Object start = s.get("start");
            // unknown start time: end it (fail safe)
            if (!(start instanceof Number n) || n.longValue() < resetAt.toEpochMilli()) out.add(id);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void signOutSessionsBefore(String subject, Instant resetAt, String keepSessionId) {
        try {
            List<Map<String, Object>> sessions = http.get().uri("/admin/realms/{realm}/users/{id}/sessions", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class);
            for (String sid : sessionsToEnd(sessions, resetAt, keepSessionId)) {
                http.delete().uri("/admin/realms/{realm}/sessions/{sid}", cfg.realm(), sid).headers(h -> h.setBearerAuth(accessToken()))
                        .retrieve().toBodilessEntity();
            }
            // offline sessions (refresh tokens kept by apps) never survive a reset
            List<Map<String, Object>> clients = http.get().uri("/admin/realms/{realm}/users/{id}/consents", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class);
            for (Map<String, Object> c : clients == null ? List.<Map<String, Object>>of() : clients) {
                http.delete().uri("/admin/realms/{realm}/users/{id}/consents/{client}", cfg.realm(), subject, c.get("clientId"))
                        .headers(h -> h.setBearerAuth(accessToken())).retrieve().toBodilessEntity();
            }
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity_provider_unavailable", "Sign-in service unavailable.");
        }
    }

    @SuppressWarnings("unchecked")
    private String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiry)) return token;
        synchronized (this) {
            if (token != null && Instant.now().isBefore(tokenExpiry)) return token;
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", cfg.clientId());
            form.add("client_secret", cfg.clientSecret());
            Map<String, Object> r = http.post().uri("/realms/{realm}/protocol/openid-connect/token", cfg.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new RestClientException("candidate provisioner could not authenticate (" + res.getStatusCode().value() + ")");
                    })
                    .body(Map.class);
            token = String.valueOf(r.get("access_token"));
            long expiresIn = ((Number) r.getOrDefault("expires_in", 60)).longValue();
            tokenExpiry = Instant.now().plusSeconds(Math.max(5, expiresIn - 30));
            return token;
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
