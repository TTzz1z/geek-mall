package com.geekmall.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PaymentSucceededConsumer {
    private static final String GROUP = "inventory-payment-succeeded";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InventoryService inventory;

    PaymentSucceededConsumer(JdbcTemplate jdbc, ObjectMapper mapper, InventoryService inventory) {
        this.jdbc = jdbc; this.mapper = mapper; this.inventory = inventory;
    }

    @RabbitListener(queues = InventoryRabbitConfiguration.PAYMENT_QUEUE)
    @Transactional
    void consume(String body) throws Exception {
        JsonNode event = mapper.readTree(body);
        String eventId = event.path("eventId").asText();
        try {
            jdbc.update("INSERT INTO event_consume_log(consumer_group,event_id,processed_at,result) VALUES(?,?,CURRENT_TIMESTAMP,'PROCESSING')", GROUP, eventId);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        inventory.deduct(event.path("payload").path("orderNo").asText());
        jdbc.update("UPDATE event_consume_log SET result='SUCCESS',processed_at=CURRENT_TIMESTAMP WHERE consumer_group=? AND event_id=?", GROUP, eventId);
    }
}
