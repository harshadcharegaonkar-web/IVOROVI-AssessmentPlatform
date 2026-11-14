package com.ivorovi.platform.process;

import com.ivorovi.platform.config.DeliveryProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The dependency checks of the heartbeat: database, Valkey and (delivery only) the candidate realm's signing keys. */
@Configuration(proxyBeanMethods = false)
class DependencyChecks {

    static final Duration SLOW = Duration.ofSeconds(1);

    @Bean
    DependencyCheck databaseCheck(JdbcClient jdbc) {
        return new DependencyCheck() {
            public String name() { return "database"; }

            public Status check() {
                long start = System.nanoTime();
                try {
                    jdbc.sql("SELECT 1").query(Integer.class).single();
                } catch (RuntimeException e) {
                    return Status.FAILED;
                }
                return elapsed(start).compareTo(SLOW) > 0 ? Status.SLOW : Status.OK;
            }
        };
    }

    @Bean
    DependencyCheck valkeyCheck(RedisConnectionFactory redis) {
        return new DependencyCheck() {
            public String name() { return "valkey"; }

            public Status check() {
                long start = System.nanoTime();
                try (var c = redis.getConnection()) {
                    c.ping();
                } catch (RuntimeException e) {
                    return Status.FAILED;
                }
                return elapsed(start).compareTo(SLOW) > 0 ? Status.SLOW : Status.OK;
            }
        };
    }

    /** The delivery process verifies candidate tokens with the realm's published keys; it must be able to fetch them. */
    @Bean
    DependencyCheck identityKeysCheck(DeliveryProperties delivery) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new DependencyCheck() {
            public String name() { return "identity_keys"; }

            public boolean appliesTo(ProcessRole role) {
                return role == ProcessRole.DELIVERY && delivery.jwkSetUri() != null && !delivery.jwkSetUri().isBlank();
            }

            public Status check() {
                long start = System.nanoTime();
                try {
                    HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(delivery.jwkSetUri())).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() != 200) return Status.FAILED;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Status.FAILED;
                } catch (Exception e) {
                    return Status.FAILED;
                }
                return elapsed(start).compareTo(SLOW) > 0 ? Status.SLOW : Status.OK;
            }
        };
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
