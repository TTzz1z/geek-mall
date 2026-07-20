package com.geekmall.inventory;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InventoryRabbitConfiguration {
    static final String EXCHANGE = "geek.domain.events";
    static final String PAYMENT_QUEUE = "geek.inventory.payment-succeeded.v1";

    @Bean DirectExchange domainExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue inventoryPaymentQueue() { return QueueBuilder.durable(PAYMENT_QUEUE).build(); }
    @Bean Binding inventoryPaymentBinding(Queue inventoryPaymentQueue, DirectExchange domainExchange) {
        return BindingBuilder.bind(inventoryPaymentQueue).to(domainExchange).with("payment.payment.succeeded.v1");
    }
}
