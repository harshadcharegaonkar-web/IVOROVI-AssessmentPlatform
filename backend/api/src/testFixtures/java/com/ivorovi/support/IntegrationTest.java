package com.ivorovi.support;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfiguration.class)
@Tag("integration")
@TestPropertySource(properties = {
        // generous defaults so functional tests are never throttled; RateLimitIT overrides them
        "ivorovi.rate-limit.user-read-per-minute=100000",
        "ivorovi.rate-limit.user-write-per-minute=100000",
        "ivorovi.rate-limit.tenant-per-minute=100000",
        "ivorovi.rate-limit.failed-auth-per-ip-per-minute=100000",
        "ivorovi.rate-limit.public-per-ip-per-minute=100000",
        // test-only probe endpoints never appear in the exported API contract
        "springdoc.paths-to-exclude=/api/v1/test-probe/**"})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("IVOROVI_DB_URL", Containers::jdbcUrl);
        r.add("IVOROVI_DB_APP_PASSWORD", () -> Containers.APP_PASSWORD);
        r.add("IVOROVI_DB_OWNER_PASSWORD", () -> Containers.OWNER_PASSWORD);
        r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/devdata,classpath:db/testdata");
        r.add("spring.data.redis.host", Containers.VALKEY::getHost);
        r.add("spring.data.redis.port", () -> Containers.VALKEY.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> Containers.REDIS_PASSWORD);
        r.add("spring.rabbitmq.host", Containers.RABBIT::getHost);
        r.add("spring.rabbitmq.port", Containers.RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", Containers.RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", Containers.RABBIT::getAdminPassword);
        r.add("IVOROVI_S3_ENDPOINT", Containers::s3Endpoint);
        r.add("ivorovi.storage.access-key", () -> "test-access");
        r.add("ivorovi.storage.secret-key", () -> "test-secret");
        r.add("ivorovi.storage.create-bucket", () -> "true");
        r.add("ivorovi.storage.bucket", () -> "ivorovi-test");
        r.add("IVOROVI_OIDC_ISSUER", () -> TestTokens.ISSUER);
        r.add("IVOROVI_OIDC_JWKS", () -> "http://jwks.invalid/certs");
        r.add("ivorovi.deployment.environment", () -> "TEST");
        r.add("ivorovi.outbox.publisher-enabled", () -> "false");
        r.add("ivorovi.features.deployment-disabled", () -> "ai.proctoring-vision");
    }

    /** Connection as the schema owner (bypasses RLS). For assertions and tamper simulations only. */
    protected static JdbcClient ownerJdbc() {
        return JdbcClient.create(dataSource("ivorovi_owner", Containers.OWNER_PASSWORD));
    }

    /** Raw connection as the application role with no tenant context. */
    protected static JdbcClient rawAppJdbc() {
        return JdbcClient.create(dataSource("ivorovi_app", Containers.APP_PASSWORD));
    }

    private static DataSource dataSource(String user, String password) {
        return new DriverManagerDataSource(Containers.jdbcUrl(), user, password);
    }

    protected static Map<String, String> auth(Fixture.Caller caller) {
        return TestTokens.bearer(TestTokens.forCaller(caller));
    }
}
