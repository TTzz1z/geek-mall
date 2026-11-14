package com.geekmall.inventory;

import com.geekmall.common.messaging.ConfirmedOutbox;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class InventoryOutboxPublisher {
    private final ConfirmedOutbox outbox;

    InventoryOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        outbox = new ConfirmedOutbox(jdbc, rabbit, "event_outbox", InventoryRabbitConfiguration.EXCHANGE,
                Set.of("inventory.stock.reserved.v1", "inventory.stock.released.v1"));
    }

    @Scheduled(fixedDelayString = "${geek.outbox.interval:2000}")
    void publish() {
        outbox.publish();
    }
}
