package com.ivorovi.platform.security;

import com.ivorovi.platform.config.SecurityProperties;
import com.ivorovi.platform.web.ProblemResponses;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, IvoroviJwtAuthenticationConverter converter,
                            ProblemResponses problems) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            if (ex instanceof TenantAccessDeniedException tad) {
                problems.write(response, HttpStatus.FORBIDDEN, "tenant_access_denied",
                        "Your account does not have access to this organization (" + tad.reason() + ").");
            } else {
                response.setHeader("WWW-Authenticate", "Bearer");
                problems.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                        "A valid access token is required.");
            }
        };
        AccessDeniedHandler denied = (request, response, ex) ->
                problems.write(response, HttpStatus.FORBIDDEN, "forbidden", "You do not have permission for this action.");

        http
            .csrf(c -> c.disable()) // stateless bearer-token API, no cookies → CSRF not applicable
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(denied)
                .jwt(j -> j.jwtAuthenticationConverter(converter)))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
            .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(tokenValidator(props));
        return decoder;
    }

    /** Issuer, expiry/not-before (default validators) and audience. Shared with tests to keep them honest. */
    public static OAuth2TokenValidator<Jwt> tokenValidator(SecurityProperties props) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(props.audience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer()), audience);
    }
}
