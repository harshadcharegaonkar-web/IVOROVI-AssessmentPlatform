package com.ivorovi.platform.security;

import com.ivorovi.platform.config.DeliveryProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * KI-79: decoder of OIDC back-channel logout tokens sent by the candidate realm when it ends a session (OpenID Connect
 * Back-Channel Logout 1.0, section 2.6). Trusts only the candidate realm's keys and issuer; requires an audience that is
 * one of the configured candidate clients, the type {@code logout+jwt}, the back-channel logout event, a session id ({@code sid}) and a subject, no
 * {@code nonce}, and an issue time within the last five minutes (and not in the future).
 */
public record CandidateLogoutTokenDecoder(JwtDecoder delegate) {

    public static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final Duration SKEW = Duration.ofSeconds(60);

    public static OAuth2TokenValidator<Jwt> validator(DeliveryProperties props) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.stream().anyMatch(props.backchannelClients()::contains) ? ok() : fail("Invalid audience");
        };
        OAuth2TokenValidator<Jwt> logout = jwt -> {
            Map<String, Object> events = jwt.getClaimAsMap("events");
            if (events == null || !events.containsKey(EVENT)) return fail("Not a back-channel logout token");
            if (jwt.hasClaim("nonce")) return fail("A logout token carries no nonce");
            String sid = jwt.getClaimAsString("sid");
            if (sid == null || sid.isBlank() || sid.length() > 100) return fail("A session id is required");
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) return fail("A subject is required");
            Instant iat = jwt.getIssuedAt();
            Instant now = Instant.now();
            if (iat == null || iat.isBefore(now.minus(MAX_AGE)) || iat.isAfter(now.plus(SKEW))) return fail("Stale or future logout token");
            return ok();
        };
        // the token type is "logout+jwt" (section 2.4); decoders built for it skip the default "JWT" type check (validateType(false))
        // (the default validator set also checks for type "JWT", so it is assembled here: issuer, timestamps, logout type)
        return new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(props.issuer()), new JwtTimestampValidator(), new JwtTypeValidator("logout+jwt"),
                audience, logout);
    }

    private static OAuth2TokenValidatorResult ok() {
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String why) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", why, null));
    }
}
