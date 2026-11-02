package com.ivorovi.platform;

import static com.ivorovi.support.Fixture.TENANT_A;
import static com.ivorovi.support.Fixture.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.ivorovi.platform.mail.EmailMessage;
import com.ivorovi.platform.mail.EmailSender;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class InfrastructureAdaptersIT extends IntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry r) {
        r.add("spring.mail.host", () -> "127.0.0.1");
        r.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
    }

    @Autowired ObjectStorage storage;
    @Autowired EmailSender email;

    @Test
    void objectsAreNamespacedPerTenant() {
        byte[] content = "answer-script".getBytes(StandardCharsets.UTF_8);
        TenantContext.runInTenant(TENANT_A, () ->
                storage.put("scripts/1.txt", new ByteArrayInputStream(content), content.length, "text/plain"));
        TenantContext.runInTenant(TENANT_A, () -> assertThat(storage.get("scripts/1.txt")).contains(content));
        TenantContext.runInTenant(TENANT_B, () -> assertThat(storage.get("scripts/1.txt")).isEmpty());
        TenantContext.runInTenant(TENANT_A, () -> storage.delete("scripts/1.txt"));
        TenantContext.runInTenant(TENANT_A, () -> assertThat(storage.get("scripts/1.txt")).isEmpty());
    }

    @Test
    void presignedUrlDownloadsObject() throws Exception {
        byte[] content = "presigned".getBytes(StandardCharsets.UTF_8);
        URI url = TenantContext.callInTenant(TENANT_A, () -> {
            storage.put("reports/r.txt", new ByteArrayInputStream(content), content.length, "text/plain");
            return storage.presignedDownload("reports/r.txt", Duration.ofMinutes(1));
        });
        assertThat(url.getPath()).contains("/t/" + TENANT_A + "/reports/r.txt");
        HttpResponse<byte[]> resp = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
                .send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo(content);
    }

    @Test
    void traversalAndMissingContextAreRejected() {
        TenantContext.runInTenant(TENANT_A, () -> {
            assertThatThrownBy(() -> storage.get("../" + TENANT_B + "/x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.get("/t/" + TENANT_B + "/x")).isInstanceOf(IllegalArgumentException.class);
        });
        assertThatThrownBy(() -> storage.get("x")).isInstanceOf(TenantContext.MissingTenantContextException.class);
    }

    @Test
    void emailIsDeliveredOverSmtp() throws Exception {
        email.send(new EmailMessage("candidate@example.test", "Your exam schedule", "Hello"));
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        var received = greenMail.getReceivedMessages()[0];
        assertThat(received.getSubject()).isEqualTo("Your exam schedule");
    }
}
