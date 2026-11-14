package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGatewayTest {
    @Test
    void forwardsTheFirstValidatedSentenceBeforeTheModelFinishes() throws Exception {
        var seen = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"First.\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { finish.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        upstream.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var model = new ModelGateway(org.mockito.Mockito.mock(JdbcTemplate.class), new ObjectMapper(),
                    "primary", "fallback", false, true, "http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "test-key", "test-model", 5000);
            var cancellation = new TurnCancellation();
            var guard = new AnswerStreamGuard("system", "safe draft", java.util.List.of(), delta -> {
                assertEquals("First.", delta);
                seen.countDown();
            });
            var result = executor.submit(() -> model.streamAnswer("system", "user", 1L, guard, cancellation));
            try {
                assertTrue(seen.await(3, TimeUnit.SECONDS));
                assertFalse(result.isDone());
            } finally {
                finish.countDown();
            }
            assertEquals("First.", result.get(3, TimeUnit.SECONDS).text());
        } finally {
            upstream.stop(0);
        }
    }

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
