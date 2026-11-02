package com.ivorovi.support;

import com.ivorovi.identity.IdentityProvisioner;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory identity provider for integration tests (the Keycloak adapter itself is exercised end to end by the
 * compose smoke test and Playwright against a real Keycloak). Mirrors the behaviour the services rely on.
 */
public class FakeIdentityProvisioner implements IdentityProvisioner {

    public final Map<String, IdpUser> byId = new ConcurrentHashMap<>();
    public final Map<String, String> passwords = new ConcurrentHashMap<>();
    public volatile boolean unavailable;

    @Override
    public Optional<IdpUser> findByEmail(String email) {
        check();
        return byId.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
    }

    @Override
    public String create(NewUser u) {
        check();
        if (new String(u.password()).length() < 12) throw new RejectedException("The password does not meet the password policy.");
        if (findByEmail(u.email()).isPresent()) throw new RejectedException("An account with this email address already exists.");
        String id = UUID.randomUUID().toString();
        byId.put(id, new IdpUser(id, u.email(), true, u.tenantId()));
        passwords.put(id, new String(u.password()));
        return id;
    }

    @Override
    public void setEnabled(String subject, boolean enabled) {
        check();
        IdpUser u = byId.get(subject);
        if (u != null) byId.put(subject, new IdpUser(u.id(), u.email(), enabled, u.tenantId()));
    }

    public void addExisting(String subject, String email, UUID tenantId) {
        byId.put(subject, new IdpUser(subject, email, true, tenantId));
    }

    private void check() {
        if (unavailable) throw new UnavailableException("simulated outage", null);
    }
}
