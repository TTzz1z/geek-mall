package com.geekmall.marketing;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MarketingRabbitConfiguration {
    static final String EXCHANGE = "geek.domain.events";
    static final String SECKILL_QUEUE = "marketing.seckill.order.q";
    static final String SECKILL_ROUTING = "marketing.seckill.order.v1";
    static final String TRADE_CREATE_ROUTING = "trade.seckill.create.v1";

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
}
