package com.ivorovi.integrations.internal;

import com.ivorovi.platform.web.ApiException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Server-side request forgery protection for every outbound call (adapters and webhooks): only http(s) URLs without
 * credentials, HTTPS unless explicitly allowed, optional host allow-list, and never loopback, link-local (cloud
 * metadata), private, multicast or wildcard addresses unless private networks are allowed for local development.
 * Hosts are re-checked at call time (DNS rebinding) and redirects are never followed.
 */
@Component
public class OutboundGuard {

    private final IntegrationsProperties props;

    OutboundGuard(IntegrationsProperties props, com.ivorovi.platform.config.DeploymentProperties deployment) {
        var env = deployment.environment();
        boolean relaxed = props.allowPrivateNetworks() || props.allowInsecureHttp();
        if (relaxed && env != com.ivorovi.platform.config.DeploymentProperties.Environment.DEVELOPMENT
                && env != com.ivorovi.platform.config.DeploymentProperties.Environment.TEST) {
            // fail closed: private-network or plain-HTTP targets are a development convenience only
            throw new IllegalStateException("ivorovi.integrations.allow-private-networks / allow-insecure-http are only permitted in DEVELOPMENT or TEST");
        }
        this.props = props;
    }

    /** Validates a configured URL (on save); returns it normalized. */
    public URI checkUrl(String raw, String field) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(field, "is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && props.allowInsecureHttp())) {
            throw invalid(field, props.allowInsecureHttp() ? "must use http or https" : "must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw invalid(field, "has no host");
        if (uri.getRawUserInfo() != null) throw invalid(field, "must not contain credentials");
        if (uri.getRawFragment() != null) throw invalid(field, "must not contain a fragment");
        checkHost(uri.getHost(), field);
        return uri;
    }

    /** Validates a host name for non-HTTP adapters (e.g. SMTP). */
    public void checkHost(String host, String field) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (!props.allowedHosts().isEmpty() && props.allowedHosts().stream().noneMatch(p -> matches(p.toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT)))) {
            throw invalid(field, "is not an allowed host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            throw invalid(field, "cannot be resolved");
        }
        for (InetAddress a : addresses) {
            if (forbidden(a)) throw invalid(field, "points to a private or reserved network address");
        }
    }

    boolean forbidden(InetAddress a) {
        byte[] b = a.getAddress();
        // cloud metadata and link-local are refused even when private networks are allowed
        if (a.isLinkLocalAddress()) return true;
        if (a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
        if (props.allowPrivateNetworks()) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) return true;
        if (b.length == 4) {
            int x = b[0] & 0xff, y = b[1] & 0xff;
            if (x == 100 && y >= 64 && y <= 127) return true;   // carrier-grade NAT
            if (x == 0 || x >= 224) return true;                 // "this" network, multicast, reserved
            if (x == 198 && (y == 18 || y == 19)) return true;   // benchmarking
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;              // unique local fc00::/7
        }
        return false;
    }

    private static boolean matches(String pattern, String host) {
        return pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) : pattern.equals(host);
    }

    private static ApiException invalid(String field, String why) {
        return ApiException.badRequest("invalid_target", "The " + field + " " + why + ".");
    }
}
