package com.geekmall.inventory;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InventoryRabbitConfiguration {
    static final String EXCHANGE = "geek.domain.events";
    static final String PAYMENT_QUEUE = "geek.inventory.payment-succeeded.v1";
    static final String DLX = "geek.domain.events.dlx";

    @Bean DirectExchange domainExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue inventoryPaymentQueue() { return QueueBuilder.durable(PAYMENT_QUEUE).build(); }
    @Bean Binding inventoryPaymentBinding(Queue inventoryPaymentQueue, DirectExchange domainExchange) {
        return BindingBuilder.bind(inventoryPaymentQueue).to(domainExchange).with("payment.payment.succeeded.v1");
    }
    @Bean DirectExchange deadExchange() { return new DirectExchange(DLX, true, false); }
    @Bean org.springframework.amqp.rabbit.retry.MessageRecoverer recoveryMessageRecoverer(org.springframework.amqp.rabbit.core.RabbitTemplate rabbit) {
        return new com.geekmall.common.messaging.DurableDeadLetterRecoverer(rabbit);
    }
    @Bean Queue inventoryPaymentDeadQueue() { return QueueBuilder.durable(PAYMENT_QUEUE + ".dlq").build(); }
    @Bean Binding inventoryPaymentDeadBinding(Queue inventoryPaymentDeadQueue) { return BindingBuilder.bind(inventoryPaymentDeadQueue).to(deadExchange()).with(PAYMENT_QUEUE + ".dlq"); }
}
