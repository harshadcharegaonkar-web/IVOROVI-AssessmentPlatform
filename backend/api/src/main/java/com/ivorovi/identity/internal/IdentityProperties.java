package com.ivorovi.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Invitation links and identity-provider provisioning. */
@ConfigurationProperties("ivorovi.identity")
public record IdentityProperties(String appBaseUrl, Duration invitationTtl, Provisioning provisioning) {

    public record Provisioning(String adminUrl, String realm, String clientId, String clientSecret) {}
}
