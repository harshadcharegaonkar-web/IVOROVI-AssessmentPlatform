package com.ivorovi.platform.security;

import com.ivorovi.platform.config.DeliveryProperties;
import com.ivorovi.platform.ratelimit.RateLimitFilters;
import com.ivorovi.platform.ratelimit.RateLimitProperties;
import com.ivorovi.platform.ratelimit.RateLimiter;
import com.ivorovi.platform.tenancy.PortalDirectory;
import com.ivorovi.platform.tenancy.TenantDirectory;
import com.ivorovi.platform.web.ProblemResponses;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The delivery boundary (C-4): a separate filter chain for {@code /api/v1/delivery/**} that trusts only candidate-realm
 * tokens ({@link CandidateTokenDecoder}), allows CORS only from the exam application's origin, binds the organization
 * from the portal key and applies candidate rate limits. Staff tokens fail here (wrong issuer, keys and audience); the
 * staff chain in {@link SecurityConfiguration} rejects candidate tokens the same way.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class DeliverySecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain delivery(HttpSecurity http, CandidateTokenDecoder decoder, DeliveryProperties props, ProblemResponses problems,
                                 RateLimiter limiter, RateLimitProperties rateLimits, PortalDirectory portals, TenantDirectory tenants) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            problems.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid candidate sign-in is required.");
        };
        AccessDeniedHandler denied = (request, response, ex) ->
                problems.write(response, HttpStatus.FORBIDDEN, "forbidden", "You do not have permission for this action.");
        http
            .securityMatcher("/api/v1/delivery/**")
            .csrf(c -> c.disable()) // bearer tokens held in memory by the exam app; no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(c -> c.configurationSource(cors(props)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/api/v1/delivery/time", "/api/v1/delivery/portals/*").permitAll()
                .anyRequest().hasAuthority(CandidateAuthentication.AUTHORITY))
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(denied)
                .jwt(j -> j.decoder(decoder.delegate()).jwtAuthenticationConverter(CandidateAuthentication::new)))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
            .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .addFilterBefore(new RateLimitFilters.FailedAuthentication(limiter, rateLimits, problems), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new DeliveryFilters.PortalTenant(portals, tenants, problems), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new DeliveryFilters.Limits(limiter, rateLimits, props, problems), DeliveryFilters.PortalTenant.class);
        return http.build();
    }

    private static UrlBasedCorsConfigurationSource cors(DeliveryProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        c.setAllowCredentials(false);
        c.setMaxAge(Duration.ofMinutes(10));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/delivery/**", c);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean
    CandidateTokenDecoder candidateTokenDecoder(DeliveryProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(CandidateTokenDecoder.validator(props));
        return new CandidateTokenDecoder(decoder);
    }
}
