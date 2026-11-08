package com.ivorovi.platform.process;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Process role and liveness heartbeat (used to detect delivery outages, C-3). */
@ConfigurationProperties("ivorovi.process")
public record ProcessProperties(ProcessRole role, boolean heartbeatEnabled, Duration heartbeatInterval) {

    public ProcessProperties {
        if (role == null) role = ProcessRole.ADMIN;
        if (heartbeatInterval == null) heartbeatInterval = Duration.ofSeconds(10);
    }
}
