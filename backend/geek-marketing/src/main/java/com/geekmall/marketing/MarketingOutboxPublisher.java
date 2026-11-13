package com.geekmall.marketing;

import com.geekmall.common.messaging.ConfirmedOutbox;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class MarketingOutboxPublisher {
    private final ConfirmedOutbox outbox;

    MarketingOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        outbox = new ConfirmedOutbox(jdbc, rabbit, "marketing_event_outbox", MarketingRabbitConfiguration.EXCHANGE,
                Set.of());
    }

    @Scheduled(fixedDelayString = "${geek.outbox.interval:2000}")
    void publish() {
        outbox.publish();
    }
}
