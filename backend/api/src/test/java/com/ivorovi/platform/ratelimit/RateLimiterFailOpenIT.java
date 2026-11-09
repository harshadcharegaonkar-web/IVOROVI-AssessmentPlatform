package com.ivorovi.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Follow-up A test 5 (KI-66): with Valkey unavailable the limiter fails open with a metric — no candidate is refused
 * because the limiter is down — and once Valkey is back, candidate-level limits apply again. Uses its own Valkey on a
 * fixed host port so it can be stopped and started without touching the shared test container.
 */
@Tag("integration")
class RateLimiterFailOpenIT {

    private GenericContainer<?> valkey;
    private LettuceConnectionFactory factory;

    @AfterEach
    void stop() {
        if (factory != null) factory.destroy();
        if (valkey != null) valkey.stop();
    }

    @Test
    void failsOpenWhileValkeyIsDownAndLimitsAgainAfterARestart() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        int hostPort = port;
        valkey = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379)
                .withCreateContainerCmdModifier(c -> c.getHostConfig().withPortBindings(
                        com.github.dockerjava.api.model.PortBinding.parse(hostPort + ":6379")))
                .waitingFor(Wait.forListeningPort());
        valkey.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(valkey.getHost(), hostPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        factory.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RateLimiter limiter = new RateLimiter(new StringRedisTemplate(factory), meters);

        // up: a candidate's 10 attempts per minute, then refused
        for (int i = 0; i < 10; i++) assertThat(limiter.acquire("code-candidate:t:c1", 10, true).allowed()).isTrue();
        assertThat(limiter.acquire("code-candidate:t:c1", 10, true).allowed()).isFalse();

        // down: every check is allowed, counters return 0, flags never fire — and the fail-open metric counts it
        valkey.getDockerClient().stopContainerCmd(valkey.getContainerId()).exec();
        for (int i = 0; i < 20; i++) assertThat(limiter.acquire("code-candidate:t:c2", 10, true).allowed()).isTrue();
        assertThat(limiter.acquire("code-hash-day:x", 20, Duration.ofDays(1), true).allowed()).isTrue();
        assertThat(limiter.increment("checkin-fail:p:1", Duration.ofMinutes(2))).isZero();
        assertThat(limiter.once("checkin-anomaly:p:1", Duration.ofMinutes(2))).isFalse();
        assertThat(meters.counter("ivorovi.ratelimit.failopen").count()).isGreaterThanOrEqualTo(23);

        // restarted (state lost, same address): candidate-level limits apply again
        valkey.getDockerClient().startContainerCmd(valkey.getContainerId()).exec();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (var c = factory.getConnection()) {
                if ("PONG".equals(c.ping())) break;
            } catch (RuntimeException notYet) {
                Thread.sleep(250);
            }
        }
        List<Boolean> after = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) after.add(limiter.acquire("code-candidate:t:c3", 10, true).allowed());
        assertThat(after.subList(0, 10)).containsOnly(true);
        assertThat(after.get(10)).isFalse();
    }
}
