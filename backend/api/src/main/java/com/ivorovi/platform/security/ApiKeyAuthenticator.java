package com.ivorovi.platform.security;

import java.util.UUID;

/**
 * Port for machine credentials (API clients, increment 1.7), implemented by the integrations module. The platform
 * filter calls it before bearer-token authentication; an implementation must resolve the organization from the key
 * alone, verify the secret in constant time inside that organization, and return a principal whose grants never exceed
 * the client's scopes.
 */
public interface ApiKeyAuthenticator {

    /** Header carrying the key ({@code Authorization: ApiKey <key>} is accepted as well). */
    String HEADER = "X-Api-Key";

    sealed interface Result permits Authenticated, Rejected {}

    record Authenticated(IvoroviPrincipal principal, UUID clientId) implements Result {}

    /** {@code reason} is a stable code for logs and the response (never echoes the key). */
    record Rejected(String reason, int status) implements Result {}

    Result authenticate(String presentedKey, String clientIp);

    /** Counts one request of an authenticated client (denied = 401/403/429 response). Best effort. */
    void recordUsage(UUID tenantId, UUID clientId, boolean denied);
}
