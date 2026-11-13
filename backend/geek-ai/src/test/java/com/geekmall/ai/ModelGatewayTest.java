package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGatewayTest {
    @Test
    void blankApiKeyIsAnObservableEstimatedFallback() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:model_gateway;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE ai_model_call_log(
                  id BIGINT PRIMARY KEY, request_id VARCHAR(64), conversation_id BIGINT,
                  model_name VARCHAR(64), latency_ms INT, prompt_tokens INT,
                  completion_tokens INT, cost DECIMAL(18,6), usage_estimated BOOLEAN,
                  status VARCHAR(32), detail VARCHAR(500), created_at TIMESTAMP
                )
                """);
        ModelGateway gateway = new ModelGateway(
                jdbc, new ObjectMapper(), "primary-model", "fallback-model", false,
                true, "https://example.invalid", " ", "remote-model", 3000);

        ModelGateway.CompletionResult result = gateway.complete(
                "ANSWER", "internal-system-sentinel", "internal-composed-prompt-sentinel", null);

        assertTrue(result.degraded());
        assertTrue(result.tokenUsageEstimated());
        assertFalse(result.text().contains("internal-"));
        assertEquals("ESTIMATED", result.asMap().get("tokenUsageSource"));
    }
}
