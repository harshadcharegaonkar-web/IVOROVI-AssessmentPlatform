package com.ivorovi.students.internal;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable queue for import jobs, bound to the outbox exchange ({@code student.import.queued}). */
@Configuration(proxyBeanMethods = false)
class StudentImportMessaging {

    @Bean
    Queue studentImportQueue() {
        return new Queue(StudentImportWorker.QUEUE, true);
    }

    @Bean
    Binding studentImportBinding(Queue studentImportQueue, TopicExchange ivoroviEventsExchange) {
        return BindingBuilder.bind(studentImportQueue).to(ivoroviEventsExchange).with("student.import.queued");
    }
}
