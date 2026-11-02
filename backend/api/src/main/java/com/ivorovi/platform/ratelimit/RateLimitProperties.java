package com.ivorovi.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits per minute. {@code failedAuthPerIp} counts only failed authentications (401), so many legitimate users
 * behind one campus NAT address are never throttled by it.
 */
@ConfigurationProperties("ivorovi.rate-limit")
public record RateLimitProperties(boolean enabled, int userReadPerMinute, int userWritePerMinute,
                                  int tenantPerMinute, int failedAuthPerIpPerMinute) {}
