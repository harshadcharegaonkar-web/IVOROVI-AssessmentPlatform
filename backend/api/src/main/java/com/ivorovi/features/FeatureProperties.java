package com.ivorovi.features;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment ceiling: flags listed here are forced OFF for every tenant of this deployment, regardless of tenant
 * settings (e.g. an on-premises site without an approved AI provider sets {@code ai.*}). Entries are exact keys
 * or prefixes ending in {@code .*}.
 */
@ConfigurationProperties("ivorovi.features")
public record FeatureProperties(List<String> deploymentDisabled) {

    public FeatureProperties {
        deploymentDisabled = deploymentDisabled == null ? List.of() : List.copyOf(deploymentDisabled);
    }

    public boolean isDeploymentDisabled(FeatureFlag flag) {
        return deploymentDisabled.stream().anyMatch(p -> p.endsWith(".*")
                ? flag.key().startsWith(p.substring(0, p.length() - 1))
                : flag.key().equals(p));
    }
}
