package com.geekmall.inventory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class InventoryDeadLetterMetrics {
    private final RabbitTemplate rabbit;
    private volatile double depth = Double.NaN;

    InventoryDeadLetterMetrics(RabbitTemplate rabbit, MeterRegistry registry) {
        this.rabbit = rabbit;
        Gauge.builder("geek.messaging.dead.letters", this, metrics -> metrics.depth)
                .tag("queue", InventoryRabbitConfiguration.PAYMENT_QUEUE + ".dlq").register(registry);
    }

    @Scheduled(fixedDelayString = "${geek.messaging.metrics-interval:15000}", initialDelay = 15000)
    void poll() {
        try {
            Long count = rabbit.execute(channel -> channel.messageCount(InventoryRabbitConfiguration.PAYMENT_QUEUE + ".dlq"));
            depth = count == null ? Double.NaN : count.doubleValue();
        } catch (RuntimeException unavailable) { depth = Double.NaN; }
    }
}
