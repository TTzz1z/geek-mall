package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelBodyDeadlineTest {
    @Test
    void realHttpBodyStallsAndEndlessTrickleRespectSeparateDeadlines() throws Exception {
        for (String mode : List.of("first", "idle", "total")) {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/v1/chat/completions", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        if ("first".equals(mode)) Thread.sleep(1500);
                        else if ("idle".equals(mode)) {
                            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"首段。\"}}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(1500);
                        } else {
                            for (int i = 0; i < 100; i++) {
                                out.write(": heartbeat\n\n".getBytes());
                                out.flush();
                                Thread.sleep(40);
                            }
                        }
                    } catch (Exception ignored) { }
                });
                server.start();
                try {
                    var gateway = new ModelGateway(mock(JdbcTemplate.class), new ObjectMapper(), "fixture", "fallback", false,
                            true, "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "fixture", 5000);
                    ReflectionTestUtils.setField(gateway, "firstByteTimeoutMs", 200);
                    ReflectionTestUtils.setField(gateway, "bodyIdleTimeoutMs", 200);
                    ReflectionTestUtils.setField(gateway, "totalTimeoutMs", 550);
                    var output = new AnswerStreamGuard("synthetic system", "首段。", List.of(), delta -> {});
                    long started = System.nanoTime();
                    var result = gateway.streamAnswer("synthetic system", "synthetic user", 1L, output, new TurnCancellation());
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertThat(result.degraded()).as(mode).isTrue();
                    assertThat(elapsed).as(mode).isLessThan(1400);
                    if ("total".equals(mode)) assertThat(elapsed).isGreaterThanOrEqualTo(450);
                } finally {
                    server.stop(0);
                    workers.shutdownNow();
                }
            }
        }
    }
}
