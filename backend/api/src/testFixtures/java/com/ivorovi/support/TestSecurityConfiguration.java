package com.ivorovi.support;

import com.ivorovi.platform.config.SecurityProperties;
import com.ivorovi.platform.security.SecurityConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    /** Same validator chain as production; trusts only the test signing key. */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.trustedPublicKey()).build();
        decoder.setJwtValidator(SecurityConfiguration.tokenValidator(props));
        return decoder;
    }
}
