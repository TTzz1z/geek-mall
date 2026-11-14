package com.geekmall.trade;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
class TradeDeadLetterMetrics {
    private final RabbitTemplate rabbit;
    private final Map<String, Double> depths = new ConcurrentHashMap<>();

    TradeDeadLetterMetrics(RabbitTemplate rabbit, MeterRegistry registry) {
        this.rabbit = rabbit;
        for (String queue : new String[]{TradeRabbitConfiguration.SECKILL_QUEUE + ".dlq", TradeRabbitConfiguration.FULFILLMENT_QUEUE + ".dlq"}) {
            depths.put(queue, Double.NaN);
            Gauge.builder("geek.messaging.dead.letters", depths, values -> values.get(queue)).tag("queue", queue).register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${geek.messaging.metrics-interval:15000}", initialDelay = 15000)
    void poll() {
        for (String queue : depths.keySet()) {
            try {
                Long count = rabbit.execute(channel -> channel.messageCount(queue));
                depths.put(queue, count == null ? Double.NaN : count.doubleValue());
            } catch (RuntimeException unavailable) { depths.put(queue, Double.NaN); }
        }
    }
}
