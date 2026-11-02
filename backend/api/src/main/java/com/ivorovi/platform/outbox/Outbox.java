package com.ivorovi.platform.outbox;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends integration events in the caller's transaction (MANDATORY: an event must commit atomically with the
 * state change it announces). {@link OutboxPublisher} delivers them to the broker at-least-once; consumers must
 * de-duplicate on the {@code message_id}.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Object payload) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))""")
                .params(id, TenantContext.currentTenantId().orElse(null), aggregateType, aggregateId, eventType,
                        json.writeValueAsString(payload))
                .update();
        return id;
    }
}
