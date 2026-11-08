package com.ivorovi.platform.security;

import com.ivorovi.platform.config.DeliveryProperties;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Decoder of the delivery boundary (a distinct type, so it never replaces the staff {@link JwtDecoder}). Trusts only the
 * candidate realm's keys and issuer, requires the {@code ivorovi-delivery} audience and a verified email address (C-1).
 */
public record CandidateTokenDecoder(JwtDecoder delegate) {

    public static OAuth2TokenValidator<Jwt> validator(DeliveryProperties props) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(props.audience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        };
        OAuth2TokenValidator<Jwt> verifiedEmail = jwt -> Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) && jwt.getClaimAsString("email") != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Email address not verified", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer()), audience, verifiedEmail);
    }
}
