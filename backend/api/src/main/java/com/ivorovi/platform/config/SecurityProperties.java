package com.ivorovi.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OIDC settings. {@code issuer} is the exact {@code iss} value tokens must carry; {@code jwkSetUri} is where the
 * API fetches signing keys (may be an internal address of the same IdP).
 */
@Validated
@ConfigurationProperties("ivorovi.security.oidc")
public record SecurityProperties(@NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience) {}
