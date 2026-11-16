package com.ivorovi.identity.internal;

import com.ivorovi.identity.IdentityProvisioner;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keycloak admin REST API through the confidential client {@code ivorovi-provisioner} (client-credentials grant;
 * the service account holds only realm-management {@code manage-users}, {@code view-users} and
 * {@code query-users}, D-P1-04; optionally {@code view-realm}, only to display the realm's password policy read-only).
 * Passwords are passed through once and never logged or stored by Ivorovi.
 */
@Component
class KeycloakIdentityProvisioner implements IdentityProvisioner {

    private static final String TENANT_ATTRIBUTE = "tenant_id";

    private final RestClient http;
    private final IdentityProperties.Provisioning cfg;
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    KeycloakIdentityProvisioner(IdentityProperties props) {
        this.cfg = props.provisioning();
        var timeouts = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(java.time.Duration.ofSeconds(5));
        timeouts.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl(cfg.adminUrl()).requestFactory(timeouts).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<IdpUser> findByEmail(String email) {
        List<Map<String, Object>> found = call(() -> http.get()
                .uri("/admin/realms/{realm}/users?email={email}&exact=true&briefRepresentation=false", cfg.realm(), email)
                .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(List.class));
        return found == null ? Optional.empty() : found.stream().findFirst().map(KeycloakIdentityProvisioner::toUser);
    }

    @Override
    public String create(NewUser u) {
        Map<String, Object> body = Map.of(
                "username", u.email(), "email", u.email(), "emailVerified", true, "enabled", true,
                "firstName", u.givenName(), "lastName", u.familyName() == null ? "" : u.familyName(),
                "attributes", Map.of(TENANT_ATTRIBUTE, List.of(u.tenantId().toString())),
                "credentials", List.of(Map.of("type", "password", "value", new String(u.password()), "temporary", false)));
        try {
            ResponseEntity<Void> r = http.post().uri("/admin/realms/{realm}/users", cfg.realm())
                    .headers(h -> h.setBearerAuth(accessToken())).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            String location = String.valueOf(r.getHeaders().getLocation());
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) throw new RejectedException(passwordMessage(e.getResponseBodyAsString()));
            if (e.getStatusCode().value() == 409) throw new RejectedException("An account with this email address already exists.");
            throw new UnavailableException("Identity provider rejected account creation (" + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new UnavailableException("Identity provider unreachable", e);
        } finally {
            Arrays.fill(u.password(), '\0');
        }
    }

    @Override
    public void setEnabled(String subject, boolean enabled) {
        call(() -> http.put().uri("/admin/realms/{realm}/users/{id}", cfg.realm(), subject)
                .headers(h -> h.setBearerAuth(accessToken())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled)).retrieve().toBodilessEntity());
        if (!enabled) {
            call(() -> http.post().uri("/admin/realms/{realm}/users/{id}/logout", cfg.realm(), subject)
                    .headers(h -> h.setBearerAuth(accessToken())).retrieve().toBodilessEntity());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void requireAuthenticatorSetup(String subject) {
        Map<String, Object> user = call(() -> http.get().uri("/admin/realms/{realm}/users/{id}", cfg.realm(), subject)
                .headers(h -> h.setBearerAuth(accessToken())).retrieve().body(Map.class));
        List<String> actions = new java.util.ArrayList<>(user == null || user.get("requiredActions") == null ? List.of() : (List<String>) user.get("requiredActions"));
        if (actions.contains("CONFIGURE_TOTP")) return;
        actions.add("CONFIGURE_TOTP");
        call(() -> http.put().uri("/admin/realms/{realm}/users/{id}", cfg.realm(), subject)
                .headers(h -> h.setBearerAuth(accessToken())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requiredActions", actions)).retrieve().toBodilessEntity());
    }

    /** Needs realm-management {@code view-realm} for the provisioner; without it the policy is simply not disclosed. */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> passwordPolicy() {
        try {
            Map<String, Object> realm = http.get().uri("/admin/realms/{realm}", cfg.realm()).headers(h -> h.setBearerAuth(accessToken()))
                    .retrieve().body(Map.class);
            Object p = realm == null ? null : realm.get("passwordPolicy");
            return p == null || String.valueOf(p).isBlank() ? Optional.empty() : Optional.of(String.valueOf(p));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new UnavailableException("Identity provider request failed (" + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new UnavailableException("Identity provider unreachable", e);
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
            Map<String, Object> r;
            try {
                r = http.post().uri("/realms/{realm}/protocol/openid-connect/token", cfg.realm())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new UnavailableException("Provisioning client could not authenticate (" + res.getStatusCode().value() + ")", null);
                        })
                        .body(Map.class);
            } catch (RestClientException e) {
                throw new UnavailableException("Identity provider unreachable", e);
            }
            token = String.valueOf(r.get("access_token"));
            long expiresIn = ((Number) r.getOrDefault("expires_in", 60)).longValue();
            tokenExpiry = Instant.now().plusSeconds(Math.max(5, expiresIn - 30));
            return token;
        }
    }

    @SuppressWarnings("unchecked")
    private static IdpUser toUser(Map<String, Object> m) {
        UUID tenant = null;
        if (m.get("attributes") instanceof Map<?, ?> attrs && attrs.get(TENANT_ATTRIBUTE) instanceof List<?> l && !l.isEmpty()) {
            try { tenant = UUID.fromString(String.valueOf(l.getFirst())); } catch (IllegalArgumentException ignored) { /* foreign value */ }
        }
        return new IdpUser(String.valueOf(m.get("id")), String.valueOf(m.get("email")), Boolean.TRUE.equals(m.get("enabled")), tenant);
    }

    /** Keycloak reports policy violations as {"error":"invalidPasswordMinLengthMessage", ...}; never echo the password. */
    private static String passwordMessage(String body) {
        if (body != null && body.contains("invalidPassword")) {
            return "The password does not meet the password policy (at least 12 characters, not your email address).";
        }
        return "The identity provider rejected the account details.";
    }
}
