package com.ivorovi.integrations.internal;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates webhook deliveries from domain events. The event's organization comes from the outbox (never from the
 * payload) and endpoints are read inside that organization's row-level-security context, so an event can only reach
 * endpoints of the organization it belongs to. One delivery per endpoint and event (redelivery is harmless).
 */
@Component
public class WebhookFanout {

    static final String QUEUE = "ivorovi.webhooks";
    private static final Logger log = LoggerFactory.getLogger(WebhookFanout.class);

    @Configuration(proxyBeanMethods = false)
    static class Messaging {
        @Bean
        Declarables webhookQueue(TopicExchange ivoroviEventsExchange) {
            Queue q = new Queue(QUEUE, true);
            List<Declarable> all = new ArrayList<>(List.of(q));
            for (String k : WebhookEvents.BINDINGS) all.add(BindingBuilder.bind(q).to(ivoroviEventsExchange).with(k));
            return new Declarables(all);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    WebhookFanout(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${ivorovi.integrations.webhooks.consumer-enabled:true}")
    void onEvent(Message message) {
        try {
            handle(IntegrationEvent.from(message));
        } catch (RuntimeException e) {
            log.warn("Webhook fan-out of event {} failed: {}", message.getMessageProperties().getMessageId(), e.getClass().getSimpleName());
        }
    }

    /** Returns the number of deliveries created. */
    public int handle(IntegrationEvent e) {
        if (e.tenantId() == null || e.id() == null || e.eventType() == null) return 0;
        Map<String, Object> details = json.readValue(e.payload() == null ? "{}" : e.payload(), new TypeReference<Map<String, Object>>() {});
        Integer n = TenantContext.callInTenant(e.tenantId(), () -> tx.execute(s -> {
            record Endpoint(UUID id, List<String> types, boolean identifiers) {}
            List<Endpoint> endpoints = jdbc.sql("SELECT id, event_types, include_identifiers FROM webhook_endpoint WHERE status IN ('ACTIVE', 'PAUSED', 'AUTO_DISABLED')")
                    .query((rs, i) -> new Endpoint(rs.getObject(1, UUID.class), ApiClientService.strings(rs.getArray(2)), rs.getBoolean(3))).list();
            int created = 0;
            for (Endpoint ep : endpoints) {
                if (ep.types().stream().noneMatch(t -> WebhookEvents.matches(t, e.eventType()))) continue;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", e.id().toString());
                payload.put("type", e.eventType());
                payload.put("occurredAt", e.occurredAt().toString());
                payload.put("tenantId", e.tenantId().toString());
                payload.put("data", WebhookEvents.data(e.eventType(), e.aggregateType(), e.aggregateId(), details, ep.identifiers()));
                created += jdbc.sql("""
                        INSERT INTO webhook_delivery (id, tenant_id, endpoint_id, event_id, event_type, payload) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                        ON CONFLICT (tenant_id, endpoint_id, event_id) WHERE replay_of IS NULL DO NOTHING""")
                        .params(Ids.newId(), e.tenantId(), ep.id(), e.id(), e.eventType(), json.writeValueAsString(payload)).update();
            }
            return created;
        }));
        return n == null ? 0 : n;
    }
}
