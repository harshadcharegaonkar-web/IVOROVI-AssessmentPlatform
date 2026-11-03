package com.ivorovi.platform;

import static com.ivorovi.support.Fixture.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.outbox.OutboxPublisher;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Event delivery: outbox row committed with business data → confirmed publication to RabbitMQ. */
class OutboxIT extends IntegrationTest {

    @Autowired Outbox outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired TopicExchange exchange;

    @Test
    void committedEventIsPublishedOnceWithMetadata() {
        String queue = "test.outbox." + UUID.randomUUID();
        var q = new org.springframework.amqp.core.Queue(queue, true, false, false);
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(exchange).with("test.outbox.#"));
        try {
            verifyDelivery(queue);
        } finally {
            admin.deleteQueue(queue);
        }
    }

    private void verifyDelivery(String queue) {

        UUID id = TenantContext.callInTenant(TENANT_A, () -> tx.execute(s ->
                outbox.append("test", "agg-1", "test.outbox.created", Map.of("hello", "world"))));
        assertThat(publisher.publishPending()).isGreaterThanOrEqualTo(1);
        // other suites leave unpublished events behind; publishing is oldest-first in batches, so drain until ours went out
        for (int i = 0; i < 100 && !ownerJdbc().sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?").param(id)
                .query(Boolean.class).single(); i++) {
            publisher.publishPending();
        }

        Message m = rabbit.receive(queue, 5000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(m.getMessageProperties().getType()).isEqualTo("test.outbox.created");
        assertThat((String) m.getMessageProperties().getHeader("tenant_id")).isEqualTo(TENANT_A.toString());
        assertThat(new String(m.getBody(), StandardCharsets.UTF_8)).contains("\"hello\"").contains("\"world\"");

        assertThat(ownerJdbc().sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        publisher.publishPending();
        assertThat(rabbit.receive(queue, 500)).as("no duplicate publication").isNull();
    }

    @Test
    void rolledBackTransactionPublishesNothing() {
        UUID[] id = new UUID[1];
        TenantContext.runInTenant(TENANT_A, () -> tx.executeWithoutResult(s -> {
            id[0] = outbox.append("test", "agg-2", "test.outbox.rolledback", Map.of());
            s.setRollbackOnly();
        }));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE id = ?").param(id[0]).query(Long.class).single()).isZero();
    }
}
