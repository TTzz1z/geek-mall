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
}
