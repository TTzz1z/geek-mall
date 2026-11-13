package com.geekmall.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConfirmedOutbox {
    private static final Logger log = LoggerFactory.getLogger(ConfirmedOutbox.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String table;
    private final String exchange;
    private final Set<String> optionalRoutes;

    public ConfirmedOutbox(JdbcTemplate jdbc, RabbitTemplate rabbit, String table, String exchange,
                           Set<String> optionalRoutes) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.table = table;
        this.exchange = exchange;
        this.optionalRoutes = Set.copyOf(optionalRoutes);
    }

    public void publish() {
        var rows = jdbc.query("""
                SELECT id,event_id,event_type,payload_json,retry_count FROM %s
                WHERE status IN ('PENDING','RETRY') AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP)
                ORDER BY created_at,id LIMIT 100
                """.formatted(table), (rs, n) -> new Event(rs.getLong("id"), rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("payload_json"), rs.getInt("retry_count")));

        var sent = new LinkedHashMap<Event, CorrelationData>();
        for (var event : rows) {
            var correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.convertAndSend(exchange, event.type(), event.payload(), message -> {
                    var properties = message.getMessageProperties();
                    properties.setMessageId(event.eventId());
                    properties.setContentType("application/json");
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, correlation);
                sent.put(event, correlation);
            } catch (RuntimeException e) {
                retry(event, e.getClass().getSimpleName());
            }
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var entry : sent.entrySet()) {
            var event = entry.getKey();
            var correlation = entry.getValue();
            try {
                var confirm = correlation.getFuture().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!confirm.isAck()) {
                    retry(event, "broker nack");
                } else if (correlation.getReturned() != null && !optionalRoutes.contains(event.type())) {
                    retry(event, "unroutable message");
                } else {
                    jdbc.update("""
                            UPDATE %s SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP,next_retry_at=NULL
                            WHERE id=? AND status IN ('PENDING','RETRY')
                            """.formatted(table), event.id());
                }
            } catch (TimeoutException | ExecutionException e) {
                retry(event, e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void retry(Event event, String reason) {
        int delay = 2 << Math.min(event.retries(), 7);
        int changed = jdbc.update("""
                UPDATE %s SET status='RETRY',retry_count=retry_count+1,
                  next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)
                WHERE id=? AND retry_count=? AND status IN ('PENDING','RETRY')
                """.formatted(table), delay, event.id(), event.retries());
        if (changed == 1) log.warn("Outbox publish deferred eventId={} type={} reason={}", event.eventId(), event.type(), reason);
    }

    private record Event(long id, String eventId, String type, String payload, int retries) {}
}
