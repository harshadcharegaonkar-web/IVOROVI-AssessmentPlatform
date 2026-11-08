package com.ivorovi.platform.outbox;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes pending outbox rows to the topic exchange (routing key = event type). Since increment 2.0 (KI-64):
 * {@code publisherThreads} workers drain the backlog in parallel, each claiming a batch with {@code FOR UPDATE SKIP
 * LOCKED} (several API replicas can also run the publisher safely), sending the whole batch and waiting for the broker's
 * confirms once per batch. A failed batch stays pending with exponential backoff (at-least-once; consumers de-duplicate
 * on the message id). Ordering across events is not guaranteed and no consumer relies on it.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private record Pending(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                           String payload, int attempts, UUID actorId, java.time.Instant createdAt) {}

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final ExecutorService workers;

    OutboxPublisher(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, OutboxProperties props) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        this.props = props;
        this.workers = Executors.newFixedThreadPool(props.publisherThreads(), Thread.ofPlatform().name("outbox-", 0).daemon().factory());
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${ivorovi.outbox.poll-interval:1s}")
    public void scheduledPublish() {
        if (props.publisherEnabled()) drain(props.drainBudget());
    }

    /** Drains the backlog with all workers until it is empty or the time budget is used; returns events published. */
    public int drain(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < props.publisherThreads(); i++) {
            tasks.add(() -> {
                int total = 0;
                while (System.nanoTime() < deadline) {
                    int n = publishPending();
                    total += Math.max(n, 0);
                    if (n < props.batchSize()) break;   // backlog empty (or failing): stop this worker
                }
                return total;
            });
        }
        int total = 0;
        try {
            for (Future<Integer> f : workers.invokeAll(tasks)) {
                try {
                    total += f.get();
                } catch (Exception e) {
                    log.warn("Outbox worker failed: {}", e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return total;
    }

    /** Publishes one batch; returns the number of events confirmed by the broker (0 when nothing is due or it failed). */
    public int publishPending() {
        Integer n = TenantContext.callAsPlatform(() -> tx.execute(status -> {
            List<Pending> batch = jdbc.sql("""
                    SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts, actor_id, created_at
                    FROM outbox_event WHERE published_at IS NULL AND available_at <= now()
                    ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED""")
                    .param(props.batchSize())
                    .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                            rs.getString("payload"), rs.getInt("attempts"),
                            rs.getObject("actor_id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                    .list();
            if (batch.isEmpty()) return 0;
            UUID[] ids = batch.stream().map(Pending::id).toArray(UUID[]::new);
            try {
                send(batch);
                jdbc.sql("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ANY (?)")
                        .param(ids).update();
                return batch.size();
            } catch (RuntimeException e) {
                int attempts = batch.stream().mapToInt(Pending::attempts).min().orElse(0);
                long backoff = Math.min(props.maxBackoff().toSeconds(), (long) Math.pow(2, Math.min(attempts, 20)));
                log.warn("Outbox batch of {} failed (attempt {}): {}", batch.size(), attempts + 1, e.toString());
                jdbc.sql("""
                        UPDATE outbox_event SET attempts = attempts + 1, last_error = ?,
                            available_at = now() + make_interval(secs => ?) WHERE id = ANY (?)""")
                        .params(truncate(e.toString()), (double) backoff, ids).update();
                return 0;
            }
        }));
        return n == null ? 0 : n;
    }

    /** Sends the batch on one channel and waits for all broker confirms once. */
    private void send(List<Pending> batch) {
        Duration timeout = props.confirmTimeout();
        rabbit.invoke(ops -> {
            for (Pending p : batch) ops.send(props.exchange(), p.eventType(), message(p));
            ops.waitForConfirmsOrDie(timeout.toMillis());
            return null;
        });
    }

    private static Message message(Pending p) {
        return MessageBuilder.withBody(p.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding("utf-8")
                .setMessageId(p.id().toString())
                .setType(p.eventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("tenant_id", p.tenantId() == null ? null : p.tenantId().toString())
                .setHeader("aggregate_type", p.aggregateType())
                .setHeader("aggregate_id", p.aggregateId())
                .setHeader("actor_id", p.actorId() == null ? null : p.actorId().toString())
                .setHeader("occurred_at", p.createdAt().toString())
                .build();
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
