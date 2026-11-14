package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.JwtService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.flyway.locations=filesystem:src/main/resources/db/migration",
        "geek.tools.approval-interval=600000", "geek.knowledge.index-interval=600000"
})
class AgentExecutionIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST_SENTENCE = "可以在订单页面查看物流进度。";
    private static final AuthPrincipal SHOPPER = new AuthPrincipal(4, "shopper", "USER", null, null, Set.of("USER"), Set.of());
    private static final Map<String, StreamScenario> scenarios = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService providerWorkers = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer model = startModel();

    @Autowired OrchestratorService agent;
    @Autowired ToolApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry config) {
        config.add("spring.datasource.url", mysql::getJdbcUrl);
        config.add("spring.datasource.username", mysql::getUsername);
        config.add("spring.datasource.password", mysql::getPassword);
        config.add("geek.llm.base-url", () -> "http://127.0.0.1:" + model.getAddress().getPort());
        config.add("geek.llm.api-key", () -> "fixture-key");
        config.add("geek.trade-service-url", AgentExecutionIT::fixtureUrl);
        config.add("geek.member-service-url", AgentExecutionIT::fixtureUrl);
        config.add("geek.elasticsearch-url", () -> "http://127.0.0.1:9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannerOutputIsValidatedBeforeApprovalAndForbiddenToolsStayDenied() {
        String conversation = newConversation();
        var turn = agent.postMessage(conversation, "请生成订单 GM1 的改地址草稿", "ORDER_LOGISTICS", SHOPPER, null);
        assertThat(turn).containsEntry("plannerMode", "LLM");
        var tools = (List<Map<String, Object>>) turn.get("tools");
        assertThat(tools).anyMatch(tool -> "formal_refund".equals(tool.get("toolName")) && "DENIED".equals(tool.get("status")));
        assertThat(tools).anyMatch(tool -> "navigate_to_page".equals(tool.get("toolName")) && "DENIED".equals(tool.get("status")));
        assertThat(tools).anyMatch(tool -> "unknown_tool".equals(tool.get("toolName")) && "DENIED".equals(tool.get("status")));
        var pending = (List<Map<String, Object>>) turn.get("confirmations");
        assertThat(pending).hasSize(1);
        String token = String.valueOf(pending.getFirst().get("confirmationToken"));
        assertThatThrownBy(() -> approvals.confirm(token, 5, "Bearer another-user")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.confirm(token, 4, "Bearer fixture-unowned-order")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.confirm(token, 4, "Bearer fixture-unowned-address")).isInstanceOf(BusinessException.class);
        assertThat(approvals.status(token, 4)).containsEntry("status", "PENDING");
        assertThat(approvals.confirm(token, 4, "Bearer fixture-owner")).containsEntry("status", "CONFIRMED");
        approvals.advance();
        approvals.advance();
        assertThat(approvals.status(token, 4)).containsEntry("status", "EXECUTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_business_draft WHERE draft_type='MODIFY_ADDRESS'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void plannerPromptEchoFallsBackWithoutExposingPlannerMetadata() {
        var turn = agent.postMessage(newConversation(), "planner 回显测试", "ORDER_LOGISTICS", SHOPPER, null);
        assertThat(turn).containsEntry("plannerMode", "RULE");
        assertThat(turn.toString()).doesNotContain("你是极客商城受控导购编排器");
    }

    @Test
    void realHttpSseEmitsValidatedSentenceBeforeProviderDone() throws Exception {
        String conversation = newConversation();
        String message = "流式测试-正常";
        var scenario = register(message, "complete");
        try (var client = HttpClient.newHttpClient(); var stream = open(client, conversation, message)) {
            assertThat(awaitEvent(stream, "message.delta").path("delta").asText()).isEqualTo(FIRST_SENTENCE);
            assertThat(scenario.providerFinished().getCount()).isEqualTo(1);
            scenario.continueModel().countDown();
            assertThat(awaitEvent(stream, "message.complete").path("content").asText())
                    .isEqualTo(FIRST_SENTENCE + "请打开我的订单。" );
        } finally {
            scenario.continueModel().countDown();
            scenarios.remove(message);
        }
    }

    @Test
    void realHttpSseResetsToPersistedFallbackAfterUnsafeTextOrEof() throws Exception {
        for (String mode : List.of("prompt", "price", "spu", "money", "eof")) {
            String conversation = newConversation();
            String message = "流式测试-" + mode;
            var scenario = register(message, mode);
            try (var client = HttpClient.newHttpClient(); var stream = open(client, conversation, message)) {
                assertThat(awaitEvent(stream, "message.delta").path("delta").asText()).isEqualTo(FIRST_SENTENCE);
                scenario.continueModel().countDown();
                SseEvent reset = nextEvent(stream);
                assertThat(reset.name()).isEqualTo("message.reset");
                assertThat(reset.data().path("reason").asText()).isEqualTo("GROUNDED_FALLBACK");
                assertThat(reset.data().path("degraded").asBoolean()).isTrue();
                String text = reset.data().path("content").asText();
                assertThat(text).contains("模型已降级").doesNotContain("¥99999", "SPU#99999", "已为你支付成功",
                        "你是极客商城受控助手", "截断内容");
                assertThat(awaitEvent(stream, "message.complete").path("content").asText()).isEqualTo(text);
                assertThat(jdbc.queryForObject("""
                        SELECT m.content FROM ai_message m JOIN ai_conversation c ON c.id=m.conversation_id
                        WHERE c.conversation_no=? AND m.role='ASSISTANT'
                        """, String.class, conversation)).isEqualTo(text);
            } finally {
                scenario.continueModel().countDown();
                scenarios.remove(message);
            }
        }
    }

    @Test
    void realHttpStreamRejectsUnauthenticatedRequests() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/v1/ai/conversations/unknown/stream"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello\"}"))
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void cancellingRealHttpTurnClosesTheProviderStream() throws Exception {
        String conversation = newConversation();
        String message = "流式测试-取消";
        var scenario = register(message, "cancel");
        try (var client = HttpClient.newHttpClient(); var stream = open(client, conversation, message)) {
            String turnId = awaitEvent(stream, "turn.accepted").path("turnId").asText();
            assertThat(awaitEvent(stream, "message.delta").path("delta").asText()).isEqualTo(FIRST_SENTENCE);
            var otherUser = new AuthPrincipal(5, "other-shopper", "USER", null, null, Set.of("USER"), Set.of());
            var forbiddenCancel = request(conversation, "/turns/" + turnId)
                    .setHeader("Authorization", "Bearer " + jwt.issue(otherUser, Duration.ofMinutes(2)))
                    .DELETE().build();
            assertThat(client.send(forbiddenCancel, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            var cancel = request(conversation, "/turns/" + turnId).DELETE().build();
            assertThat(client.send(cancel, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            scenario.continueModel().countDown();
            assertThat(scenario.providerClosed().await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(nextEvent(stream)).isNull();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ai_message m JOIN ai_conversation c ON c.id=m.conversation_id
                    WHERE c.conversation_no=? AND m.role='ASSISTANT'
                    """, Integer.class, conversation)).isZero();
        } finally {
            scenario.continueModel().countDown();
            scenarios.remove(message);
        }
    }

    private String newConversation() {
        return String.valueOf(agent.createConversation(SHOPPER, "ORDER_LOGISTICS", Map.of()).get("conversationNo"));
    }

    private HttpRequest.Builder request(String conversation, String suffix) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/conversations/" + conversation + suffix))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + jwt.issue(SHOPPER, Duration.ofMinutes(2)));
    }

    private BufferedReader open(HttpClient client, String conversation, String message) throws Exception {
        var request = request(conversation, "/stream").header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("message", message, "scene", "ORDER_LOGISTICS"))))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private static JsonNode awaitEvent(BufferedReader stream, String name) throws IOException {
        for (SseEvent event; (event = nextEvent(stream)) != null;) {
            if (name.equals(event.name())) return event.data();
            if ("error".equals(event.name())) throw new AssertionError(event);
        }
        throw new AssertionError("Missing SSE event " + name);
    }

    private static SseEvent nextEvent(BufferedReader stream) throws IOException {
        String event = null;
        for (String line; (line = stream.readLine()) != null;) {
            if (line.startsWith("event:")) event = line.substring(6).strip();
            if (event != null && line.startsWith("data:")) return new SseEvent(event, JSON.readTree(line.substring(5)));
        }
        return null;
    }

    private record SseEvent(String name, JsonNode data) {}

    private static StreamScenario register(String message, String mode) {
        var scenario = new StreamScenario(mode, new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        scenarios.put(message, scenario);
        return scenario;
    }

    private record StreamScenario(String mode, CountDownLatch continueModel, CountDownLatch providerFinished,
                                  CountDownLatch providerClosed) {}

    @AfterAll
    static void stop() {
        model.stop(0);
        providerWorkers.shutdownNow();
    }

    private static HttpServer startModel() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(providerWorkers);
            server.createContext("/v1/chat/completions", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody());
                String prompt = request.path("messages").path(1).path("content").asText();
                String system = request.path("messages").path(0).path("content").asText();
                if (request.path("stream").asBoolean()) {
                    var scenario = scenarios.entrySet().stream().filter(e -> prompt.contains(e.getKey())).findFirst().orElseThrow().getValue();
                    streamModel(exchange, scenario, system);
                    return;
                }
                String content = "可以在订单页面查询处理进度。";
                if (request.has("response_format")) {
                    content = prompt.contains("planner 回显测试")
                            ? JSON.writeValueAsString(Map.of("agent", "ORDER_LOGISTICS", "user_goal", system, "tool_plan", List.of()))
                            : prompt.contains("流式测试-") ? """
                            {"agent":"ORDER_LOGISTICS","tool_plan":[{"name":"navigate_to_page","args":{"path":"/user/orders"}}]}
                            """ : """
                        {"agent":"ORDER_LOGISTICS","confidence":0.99,"user_goal":"draft","slots":{},"stop":true,
                         "tool_plan":[
                          {"name":"formal_refund","args":{"approved":true}},
                          {"name":"unknown_tool","args":{}},
                          {"name":"navigate_to_page","args":{"path":"//example.invalid"}},
                          {"name":"modify_address_draft","args":{"orderNo":"GM1","addressId":42}}
                         ]}
                        """;
                }
                json(exchange, 200, Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            });
            server.createContext("/api/v1/trade/orders", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (Set.of("Bearer fixture-owner", "Bearer fixture-unowned-address").contains(String.valueOf(authorization))) {
                    json(exchange, 200, Map.of("data", Map.of("orderNo", "GM1", "status", "PAID")));
                } else {
                    json(exchange, 404, Map.of("message", "订单不存在"));
                }
            });
            server.createContext("/api/v1/user/addresses/42", exchange -> {
                if ("Bearer fixture-owner".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    json(exchange, 200, Map.of("data", Map.of("id", 42)));
                } else {
                    json(exchange, 404, Map.of("message", "收货地址不存在"));
                }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void streamModel(HttpExchange exchange, StreamScenario scenario, String system) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            delta(out, FIRST_SENTENCE);
            scenario.continueModel().await(8, TimeUnit.SECONDS);
            switch (scenario.mode()) {
                case "prompt" -> {
                    delta(out, system.substring(0, 5));
                    delta(out, system.substring(5));
                }
                case "price" -> delta(out, "此商品售价 ¥99999。");
                case "spu" -> delta(out, "推荐查看 SPU#99999。");
                case "money" -> delta(out, "已为你支付成功。");
                case "eof" -> delta(out, "截断内容");
                case "complete" -> {
                    delta(out, "请打开我的订单。");
                    write(out, "data: [DONE]\n\n");
                }
                case "cancel" -> {
                    for (int i = 0; i < 100; i++) {
                        write(out, ": keep-alive\n\n");
                        Thread.sleep(50);
                    }
                }
                default -> throw new IllegalArgumentException(scenario.mode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            scenario.providerClosed().countDown();
        } finally {
            scenario.providerFinished().countDown();
        }
    }

    private static String fixtureUrl() {
        return "http://127.0.0.1:" + model.getAddress().getPort();
    }

    private static void delta(OutputStream out, String content) throws IOException {
        write(out, "data: " + JSON.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", content))))) + "\n\n");
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void json(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] body = JSON.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
}
