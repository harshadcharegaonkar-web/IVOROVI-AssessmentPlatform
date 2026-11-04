package com.ivorovi.integrations.internal;

import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * KI-59 lifecycle workflow. When a user is suspended or deactivated: their delegated API clients are suspended (no
 * automatic resumption) and service identities they look after start the 30-day custodian clock. Requests are already
 * refused by the live check before this runs; this step makes the state visible and actionable.
 */
@Component
public class IntegrationLifecycle {

    static final String QUEUE = "ivorovi.integrations.lifecycle";
    private static final Logger log = LoggerFactory.getLogger(IntegrationLifecycle.class);

    @Configuration(proxyBeanMethods = false)
    static class Messaging {
        @Bean
        Declarables integrationLifecycleQueue(TopicExchange ivoroviEventsExchange) {
            Queue q = new Queue(QUEUE, true);
            List<Declarable> all = new ArrayList<>(List.of(q));
            for (String k : List.of("user.suspended", "user.deactivated", "user.reactivated")) all.add(BindingBuilder.bind(q).to(ivoroviEventsExchange).with(k));
            return new Declarables(all);
        }
    }

    private final ApiClientService clients;
    private final ServiceIdentityService identities;
    private final TransactionTemplate tx;

    IntegrationLifecycle(ApiClientService clients, ServiceIdentityService identities, TransactionTemplate tx) {
        this.clients = clients;
        this.identities = identities;
        this.tx = tx;
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${ivorovi.integrations.lifecycle-consumer-enabled:true}")
    void onEvent(Message message) {
        try {
            handle(IntegrationEvent.from(message));
        } catch (RuntimeException e) {
            log.warn("Integration lifecycle handling of {} failed: {}", message.getMessageProperties().getMessageId(), e.getClass().getSimpleName());
            throw e;   // redelivered: the handling is idempotent
        }
    }

    /** Returns the number of clients suspended (for tests and logs). Idempotent. */
    public int handle(IntegrationEvent e) {
        if (e.tenantId() == null || e.aggregateId() == null) return 0;
        UUID user = UUID.fromString(e.aggregateId());
        Integer n = TenantContext.callInTenant(e.tenantId(), () -> tx.execute(s -> switch (e.eventType()) {
            case "user.suspended", "user.deactivated" -> {
                identities.custodianLost(user);
                yield clients.suspendForOwner(user, e.eventType());
            }
            case "user.reactivated" -> {
                identities.checkCustodians(java.time.Instant.now());   // clears the clock where both custodians are active again
                yield 0;                                                // clients stay suspended until a manager resumes them
            }
            default -> 0;
        }));
        return n == null ? 0 : n;
    }
}
