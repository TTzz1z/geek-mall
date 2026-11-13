package com.geekmall.trade;

import com.geekmall.common.messaging.ConfirmedOutbox;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class TradeOutboxPublisher {
    private final ConfirmedOutbox outbox;

    TradeOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        outbox = new ConfirmedOutbox(jdbc, rabbit, "event_outbox", TradeRabbitConfiguration.EXCHANGE,
                Set.of("trade.order.created.v1", "trade.order.cancelled.v1"));
    }

    @Scheduled(fixedDelayString = "${geek.outbox.interval:2000}")
    void publish() {
        outbox.publish();
    }
}
