package com.ivorovi.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * One outbox event as consumers see it (from the broker, or straight from the outbox table in tests). {@code id} is the
 * outbox message id and is the de-duplication key for every consumer. {@code actorId} is the user whose request caused
 * the event ({@code null} for background work).
 */
public record IntegrationEvent(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType, String payload, UUID actorId,
                               Instant occurredAt) {

    public static IntegrationEvent from(Message message) {
        MessageProperties p = message.getMessageProperties();
        return new IntegrationEvent(uuid(p.getMessageId()), uuid(p.getHeaders().get("tenant_id")), str(p.getHeaders().get("aggregate_type")),
                str(p.getHeaders().get("aggregate_id")), p.getType() != null ? p.getType() : p.getReceivedRoutingKey(),
                new String(message.getBody(), StandardCharsets.UTF_8), uuid(p.getHeaders().get("actor_id")),
                p.getHeaders().get("occurred_at") == null ? Instant.now() : Instant.parse(p.getHeaders().get("occurred_at").toString()));
    }

    private static UUID uuid(Object o) {
        return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
