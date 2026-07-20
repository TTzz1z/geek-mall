package com.geekmall.inventory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
class InventoryOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    InventoryOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) { this.jdbc = jdbc; this.rabbit = rabbit; }

    @Scheduled(fixedDelayString = "${geek.outbox.interval:2000}")
    void publish() {
        var rows = jdbc.queryForList("SELECT id,event_id,event_type,payload_json,retry_count FROM event_outbox WHERE status IN ('PENDING','RETRY') AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP) ORDER BY created_at LIMIT 100");
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            try {
                rabbit.convertAndSend(InventoryRabbitConfiguration.EXCHANGE, String.valueOf(row.get("event_type")), row.get("payload_json"), message -> {
                    message.getMessageProperties().setMessageId(String.valueOf(row.get("event_id")));
                    message.getMessageProperties().setContentType("application/json");
                    return message;
                });
                jdbc.update("UPDATE event_outbox SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RETRY')", id);
            } catch (Exception exception) {
                int retry = ((Number) row.get("retry_count")).intValue() + 1;
                jdbc.update("UPDATE event_outbox SET status=?,retry_count=?,next_retry_at=? WHERE id=?", retry >= 8 ? "FAILED" : "RETRY", retry, Timestamp.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(retry, 8)))), id);
            }
        }
    }
}
