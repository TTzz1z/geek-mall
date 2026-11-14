package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import com.geekmall.common.api.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SeckillOrderConsumer {
    private static final String GROUP = "trade-seckill-order";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SeckillOrderService seckillOrders;

    SeckillOrderConsumer(JdbcTemplate jdbc, ObjectMapper mapper, SeckillOrderService seckillOrders) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.seckillOrders = seckillOrders;
    }

    @RabbitListener(queues = TradeRabbitConfiguration.SECKILL_QUEUE)
    @Transactional
    void consume(String body) throws Exception {
        JsonNode event;
        try {
            event = mapper.readTree(body);
            if (event == null || !event.path("eventId").isTextual() || event.path("eventId").asText().isBlank()
                    || !TradeRabbitConfiguration.SECKILL_ROUTING.equals(event.path("eventType").asText())
                    || !event.path("payload").isObject()) {
                throw new IllegalArgumentException("事件标识或类型无效");
            }
        } catch (Exception invalid) {
            throw new AmqpRejectAndDontRequeueException("无效的秒杀事件", invalid);
        }
        String eventId = event.path("eventId").asText();
        try {
            jdbc.update("INSERT INTO trade_event_consume_log(id,consumer_group,event_id,processed_at,result) VALUES(?,?,?,CURRENT_TIMESTAMP,'PROCESSING')",
                    com.geekmall.common.domain.DistributedId.next(), GROUP, eventId);
        } catch (DuplicateKeyException e) {
            return;
        }
        JsonNode payload = event.path("payload");
        try {
            seckillOrders.enqueue(payload);
        } catch (BusinessException invalid) {
            throw new AmqpRejectAndDontRequeueException("无效的秒杀订单请求", invalid);
        }
        jdbc.update("UPDATE trade_event_consume_log SET result='SUCCESS',processed_at=CURRENT_TIMESTAMP WHERE consumer_group=? AND event_id=?", GROUP, eventId);
    }
}
