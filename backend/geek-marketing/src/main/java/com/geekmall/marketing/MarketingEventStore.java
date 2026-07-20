package com.geekmall.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
class MarketingEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    MarketingEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void append(String aggregateType, String aggregateId, String type, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = Map.of(
                "eventId", eventId,
                "eventType", type,
                "occurredAt", Instant.now().toString(),
                "producer", "geek-marketing",
                "aggregateId", aggregateId,
                "version", 1,
                "payload", payload);
        try {
            jdbc.update(
                    "INSERT INTO marketing_event_outbox(id,event_id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP)",
                    DistributedId.next(), eventId, aggregateType, aggregateId, type, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
