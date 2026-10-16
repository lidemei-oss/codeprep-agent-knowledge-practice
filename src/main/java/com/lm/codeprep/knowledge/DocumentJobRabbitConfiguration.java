package com.lm.codeprep.knowledge;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "codeprep.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobRabbitConfiguration {
    @Bean
    DirectExchange documentExchange(@Value("${codeprep.rabbitmq.exchange}") String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    DirectExchange documentDeadLetterExchange(@Value("${codeprep.rabbitmq.dead-letter-exchange}") String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    Queue documentQueue(@Value("${codeprep.rabbitmq.queue}") String name,
                        @Value("${codeprep.rabbitmq.dead-letter-exchange}") String dlx,
                        @Value("${codeprep.rabbitmq.dead-letter-routing-key}") String deadLetterRoutingKey) {
        return QueueBuilder.durable(name).deadLetterExchange(dlx).deadLetterRoutingKey(deadLetterRoutingKey).build();
    }

    @Bean
    Queue documentDeadLetterQueue(@Value("${codeprep.rabbitmq.dead-letter-queue}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    Binding documentBinding(Queue documentQueue, DirectExchange documentExchange,
                            @Value("${codeprep.rabbitmq.routing-key}") String routingKey) {
        return BindingBuilder.bind(documentQueue).to(documentExchange).with(routingKey);
    }

    @Bean
    Binding documentDeadLetterBinding(Queue documentDeadLetterQueue,
                                      DirectExchange documentDeadLetterExchange,
                                      @Value("${codeprep.rabbitmq.dead-letter-routing-key}") String routingKey) {
        return BindingBuilder.bind(documentDeadLetterQueue).to(documentDeadLetterExchange).with(routingKey);
    }
}
