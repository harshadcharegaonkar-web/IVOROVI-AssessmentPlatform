package com.ivorovi.platform.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    /** Durable topic exchange for all integration events. Consumers bind their own queues by routing pattern. */
    @Bean
    TopicExchange ivoroviEventsExchange(OutboxProperties props) {
        return new TopicExchange(props.exchange(), true, false);
    }
}
