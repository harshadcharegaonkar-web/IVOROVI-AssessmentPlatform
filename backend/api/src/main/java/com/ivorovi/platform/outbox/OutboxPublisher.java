package com.ivorovi.platform.outbox;

import com.ivorovi.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
 * Publishes pending outbox rows to the topic exchange (routing key = event type), waiting for broker confirms.
 * Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several API replicas can run the publisher safely.
 * Failures back off exponentially; the row stays pending until confirmed.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private record Pending(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                           String payload, int attempts) {}

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    OutboxPublisher(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, OutboxProperties props) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ivorovi.outbox.poll-interval:1s}")
    public void scheduledPublish() {
        if (props.publisherEnabled()) publishPending();
    }

    /** Publishes one batch; returns the number of events confirmed by the broker. */
    public int publishPending() {
        Integer n = TenantContext.callAsPlatform(() -> tx.execute(status -> {
            List<Pending> batch = jdbc.sql("""
                    SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts
                    FROM outbox_event WHERE published_at IS NULL AND available_at <= now()
                    ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED""")
                    .param(props.batchSize())
                    .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                            rs.getString("payload"), rs.getInt("attempts")))
                    .list();
            int published = 0;
            for (Pending p : batch) {
                try {
                    send(p);
                    jdbc.sql("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?")
                            .param(p.id()).update();
                    published++;
                } catch (RuntimeException e) {
                    long backoff = Math.min(props.maxBackoff().toSeconds(), (long) Math.pow(2, Math.min(p.attempts(), 20)));
                    log.warn("Outbox publish failed id={} type={} attempt={}: {}", p.id(), p.eventType(), p.attempts() + 1, e.toString());
                    jdbc.sql("""
                            UPDATE outbox_event SET attempts = attempts + 1, last_error = ?,
                                available_at = now() + make_interval(secs => ?) WHERE id = ?""")
                            .params(truncate(e.toString()), (double) backoff, p.id()).update();
                }
            }
            return published;
        }));
        return n == null ? 0 : n;
    }

    private void send(Pending p) {
        Message message = MessageBuilder.withBody(p.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding("utf-8")
                .setMessageId(p.id().toString())
                .setType(p.eventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("tenant_id", p.tenantId() == null ? null : p.tenantId().toString())
                .setHeader("aggregate_type", p.aggregateType())
                .setHeader("aggregate_id", p.aggregateId())
                .build();
        Duration timeout = props.confirmTimeout();
        rabbit.invoke(ops -> {
            ops.send(props.exchange(), p.eventType(), message);
            ops.waitForConfirmsOrDie(timeout.toMillis());
            return null;
        });
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
