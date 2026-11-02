package com.ivorovi.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/** Singleton containers shared by all integration tests (started once per JVM). */
public final class Containers {

    public static final String OWNER_PASSWORD = "owner-" + UUID.randomUUID();
    public static final String APP_PASSWORD = "app-" + UUID.randomUUID();
    public static final String REDIS_PASSWORD = "redis-" + UUID.randomUUID();

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15")
            .withEnv("IVOROVI_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("IVOROVI_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(repoFile("deploy/compose/postgres/init/01-init.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-init.sh");

    public static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    public static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine")
            .withCommand("valkey-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    public static final GenericContainer<?> OBJECTSTORE = new GenericContainer<>("chrislusf/seaweedfs:4.51")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-master.volumeSizeLimitMB=64", "-volume.max=10")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/status").forPort(8333).forStatusCode(200));

    static {
        java.util.stream.Stream.of(POSTGRES, RABBIT, VALKEY, OBJECTSTORE).parallel().forEach(GenericContainer::start);
    }

    private Containers() {}

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/ivorovi";
    }

    public static String s3Endpoint() {
        return "http://" + OBJECTSTORE.getHost() + ":" + OBJECTSTORE.getMappedPort(8333);
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(relative))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("Cannot locate " + relative);
        return dir.resolve(relative);
    }
}
