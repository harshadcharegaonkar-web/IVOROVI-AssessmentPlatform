package com.ivorovi.platform.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment-level settings. {@code mode} toggles operational capabilities only (e.g. self-service signup),
 * never business behaviour. {@code environment} guards development-only tooling such as the data seeder.
 */
@Validated
@ConfigurationProperties("ivorovi.deployment")
public record DeploymentProperties(@NotNull Mode mode, @NotNull Environment environment) {

    public enum Mode { SAAS, DEDICATED, ON_PREM }

    public enum Environment { DEVELOPMENT, TEST, STAGING, PRODUCTION }
}
