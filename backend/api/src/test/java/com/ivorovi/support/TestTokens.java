package com.ivorovi.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues real RS256-signed tokens for tests. The API validates them with the production validator chain
 * (issuer, audience, expiry); only the key source differs from production.
 */
public final class TestTokens {

    public static final String ISSUER = "https://idp.test/realms/ivorovi";
    public static final String AUDIENCE = "ivorovi-api";

    private static final KeyPair TRUSTED = generate();
    private static final KeyPair UNTRUSTED = generate();

    private TestTokens() {}

    public static RSAPublicKey trustedPublicKey() {
        return (RSAPublicKey) TRUSTED.getPublic();
    }

    public static String forCaller(Fixture.Caller caller) {
        return token(caller.subject(), caller.tenantId().toString(), b -> {});
    }

    public static String token(String subject, String tid, Consumer<JwtClaimsSet.Builder> customizer) {
        return sign(TRUSTED, subject, tid, customizer);
    }

    public static String signedByUntrustedKey(Fixture.Caller caller) {
        return sign(UNTRUSTED, caller.subject(), caller.tenantId().toString(), b -> {});
    }

    private static String sign(KeyPair keys, String subject, String tid, Consumer<JwtClaimsSet.Builder> customizer) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate())
                .keyID(UUID.randomUUID().toString()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject(subject).audience(List.of(AUDIENCE))
                .issuedAt(now).expiresAt(now.plusSeconds(300));
        if (tid != null) claims.claim("tid", tid);
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    public static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
