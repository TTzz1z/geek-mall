package com.geekmall.product;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProductRabbitConfiguration {
    static final String EXCHANGE = "geek.domain.events";
    @Bean DirectExchange domainExchange() { return new DirectExchange(EXCHANGE, true, false); }
}
