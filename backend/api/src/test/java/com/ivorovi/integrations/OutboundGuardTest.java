package com.ivorovi.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.integrations.internal.IntegrationsProperties;
import com.ivorovi.integrations.internal.OutboundGuard;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.web.ApiException;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SSRF protection of every outbound call (adapters and webhooks), with production settings. */
class OutboundGuardTest {

    private static OutboundGuard guard(boolean privateNets, boolean http, List<String> hosts, DeploymentProperties.Environment env) throws Exception {
        IntegrationsProperties p = new IntegrationsProperties(privateNets, http, hosts, Duration.ofSeconds(5), 3, 3, Duration.ofMinutes(15), null);
        Constructor<OutboundGuard> c = OutboundGuard.class.getDeclaredConstructor(IntegrationsProperties.class, DeploymentProperties.class);
        c.setAccessible(true);
        return c.newInstance(p, deployment(env));
    }

    private static DeploymentProperties deployment(DeploymentProperties.Environment env) {
        return new DeploymentProperties(DeploymentProperties.Mode.SAAS, env);
    }

    @Test
    void productionSettingsRefuseInternalTargets() throws Exception {
        OutboundGuard g = guard(false, false, List.of(), DeploymentProperties.Environment.PRODUCTION);
        for (String url : List.of("https://127.0.0.1/x", "https://localhost/x", "https://10.1.2.3/", "https://192.168.1.1/", "https://172.16.0.5/",
                "https://169.254.169.254/latest/meta-data/", "https://[::1]/", "https://[fd00::1]/", "https://0.0.0.0/", "https://100.64.1.1/")) {
            assertThatThrownBy(() -> g.checkUrl(url, "URL")).as(url).isInstanceOf(ApiException.class).hasMessageContaining("private or reserved");
        }
        assertThatThrownBy(() -> g.checkUrl("http://93.184.215.14/", "URL")).hasMessageContaining("https");
        assertThatThrownBy(() -> g.checkUrl("file:///etc/passwd", "URL")).hasMessageContaining("https");
        assertThatThrownBy(() -> g.checkUrl("https://user:pw@93.184.215.14/", "URL")).hasMessageContaining("credentials");
        assertThat(g.checkUrl("https://93.184.215.14/hook", "URL").getHost()).isEqualTo("93.184.215.14");
    }

    @Test
    void allowListRestrictsHosts() throws Exception {
        OutboundGuard g = guard(false, false, List.of("*.example.edu"), DeploymentProperties.Environment.PRODUCTION);
        assertThatThrownBy(() -> g.checkUrl("https://93.184.215.14/", "URL")).hasMessageContaining("not an allowed host");
    }

    @Test
    void developmentRelaxationsStillRefuseLinkLocalAndCannotRunInProduction() throws Exception {
        OutboundGuard dev = guard(true, true, List.of(), DeploymentProperties.Environment.DEVELOPMENT);
        assertThat(dev.checkUrl("http://127.0.0.1:8025/", "URL")).isNotNull();
        assertThatThrownBy(() -> dev.checkUrl("http://169.254.169.254/", "URL")).hasMessageContaining("private or reserved");
        assertThatThrownBy(() -> guard(true, false, List.of(), DeploymentProperties.Environment.PRODUCTION))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard(false, true, List.of(), DeploymentProperties.Environment.STAGING))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
