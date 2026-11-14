package com.geekmall.trade;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TradeRabbitConfiguration {
    static final String EXCHANGE = "geek.domain.events";
    static final String SECKILL_QUEUE = "trade.seckill.order.q";
    static final String SECKILL_ROUTING = "marketing.seckill.order.v1";
    static final String FULFILLMENT_QUEUE = "trade.fulfillment.status.q";
    static final String DLX = "geek.domain.events.dlx";

    @Bean
    DirectExchange domainExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue seckillQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE).build();
    }

    @Bean
    Binding seckillBinding(Queue seckillQueue, DirectExchange domainExchange) {
        return BindingBuilder.bind(seckillQueue).to(domainExchange).with(SECKILL_ROUTING);
    }

    @Bean
    Queue fulfillmentStatusQueue() {
        return QueueBuilder.durable(FULFILLMENT_QUEUE).build();
    }

    @Bean
    Binding fulfillmentStatusBinding(Queue fulfillmentStatusQueue, DirectExchange domainExchange) {
        return BindingBuilder.bind(fulfillmentStatusQueue).to(domainExchange)
                .with("inventory.fulfillment.status-changed.v1");
    }

    @Bean DirectExchange deadExchange() { return new DirectExchange(DLX, true, false); }
    @Bean org.springframework.amqp.rabbit.retry.MessageRecoverer recoveryMessageRecoverer(org.springframework.amqp.rabbit.core.RabbitTemplate rabbit) {
        return new com.geekmall.common.messaging.DurableDeadLetterRecoverer(rabbit);
    }
    @Bean Queue seckillDeadQueue() { return QueueBuilder.durable(SECKILL_QUEUE + ".dlq").build(); }
    @Bean Queue fulfillmentDeadQueue() { return QueueBuilder.durable(FULFILLMENT_QUEUE + ".dlq").build(); }
    @Bean Binding seckillDeadBinding(Queue seckillDeadQueue) { return BindingBuilder.bind(seckillDeadQueue).to(deadExchange()).with(SECKILL_QUEUE + ".dlq"); }
    @Bean Binding fulfillmentDeadBinding(Queue fulfillmentDeadQueue) { return BindingBuilder.bind(fulfillmentDeadQueue).to(deadExchange()).with(FULFILLMENT_QUEUE + ".dlq"); }
}
