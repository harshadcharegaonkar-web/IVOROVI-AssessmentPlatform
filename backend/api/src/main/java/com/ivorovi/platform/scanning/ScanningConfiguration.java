package com.ivorovi.platform.scanning;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/** Registers the ClamAV adapter when {@code ivorovi.scanning.clamd-host} is set; otherwise no scanner exists (fail closed). */
@Configuration
class ScanningConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ivorovi.scanning.clamd-host")
    MalwareScanner clamAvScanner(@Value("${ivorovi.scanning.clamd-host}") String host, @Value("${ivorovi.scanning.clamd-port:3310}") int port,
                                 @Value("${ivorovi.scanning.timeout:PT30S}") Duration timeout) {
        return new ClamAvScanner(host, port, timeout);
    }
}
