package com.geekmall.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
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
    void consume(String body) {
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AmqpRejectAndDontRequeueException("无法解析支付成功事件", e);
        }
        JsonNode eventIdNode = event.path("eventId");
        JsonNode orderNoNode = event.path("payload").path("orderNo");
        if (!eventIdNode.isTextual() || eventIdNode.asText().isBlank()
                || !orderNoNode.isTextual() || orderNoNode.asText().isBlank()
                || !"payment.payment.succeeded.v1".equals(event.path("eventType").asText())) {
            throw new AmqpRejectAndDontRequeueException("无效的支付成功事件");
        }
        String eventId = eventIdNode.asText();
        String orderNo = orderNoNode.asText();
        try {
            jdbc.update("INSERT INTO event_consume_log(consumer_group,event_id,processed_at,result) VALUES(?,?,CURRENT_TIMESTAMP,'PROCESSING')", GROUP, eventId);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        inventory.deduct(orderNo);
        jdbc.update("UPDATE event_consume_log SET result='SUCCESS',processed_at=CURRENT_TIMESTAMP WHERE consumer_group=? AND event_id=?", GROUP, eventId);
    }
}
