package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.security.AuthPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.flyway.locations=filesystem:src/main/resources/db/migration",
        "geek.tools.approval-interval=600000", "geek.knowledge.index-interval=600000"
})
class AgentExecutionIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    static final HttpServer model = startModel();

    @Autowired OrchestratorService agent;
    @Autowired ToolApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry config) {
        config.add("spring.datasource.url", mysql::getJdbcUrl);
        config.add("spring.datasource.username", mysql::getUsername);
        config.add("spring.datasource.password", mysql::getPassword);
        config.add("geek.llm.base-url", () -> "http://127.0.0.1:" + model.getAddress().getPort());
        config.add("geek.llm.api-key", () -> "fixture-key");
        config.add("geek.elasticsearch-url", () -> "http://127.0.0.1:9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannerOutputIsValidatedBeforeApprovalAndForbiddenToolsStayDenied() {
        var user = new AuthPrincipal(4, "shopper", "USER", null, null, Set.of("USER"), Set.of());
        String conversation = String.valueOf(agent.createConversation(user, "ORDER_LOGISTICS", Map.of()).get("conversationNo"));
        var turn = agent.postMessage(conversation, "请生成订单 GM1 的改地址草稿", "ORDER_LOGISTICS", user, null);
        var tools = (List<Map<String, Object>>) turn.get("tools");
        assertThat(tools).anyMatch(tool -> "formal_refund".equals(tool.get("toolName")) && "DENIED".equals(tool.get("status")));
        assertThat(tools).anyMatch(tool -> "navigate_to_page".equals(tool.get("toolName")) && "DENIED".equals(tool.get("status")));
        var pending = (List<Map<String, Object>>) turn.get("confirmations");
        assertThat(pending).hasSize(1);
        String token = String.valueOf(pending.getFirst().get("confirmationToken"));
        assertThat(approvals.confirm(token, 4)).containsEntry("status", "CONFIRMED");
        approvals.advance();
        approvals.advance();
        assertThat(approvals.status(token, 4)).containsEntry("status", "EXECUTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_business_draft WHERE draft_type='MODIFY_ADDRESS'", Integer.class))
                .isEqualTo(1);
    }

    @AfterAll
    static void stop() {
        model.stop(0);
    }

    private static HttpServer startModel() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var mapper = new ObjectMapper();
            server.createContext("/v1/chat/completions", exchange -> {
                var request = mapper.readTree(exchange.getRequestBody());
                String content = request.has("response_format") ? """
                        {"agent":"ORDER_LOGISTICS","confidence":0.99,"user_goal":"draft","slots":{},"stop":true,
                         "tool_plan":[
                          {"name":"formal_refund","args":{"approved":true}},
                          {"name":"navigate_to_page","args":{"path":"//example.invalid"}},
                          {"name":"modify_address_draft","args":{"orderNo":"GM1","addressId":42}}
                         ]}
                        """ : "已生成待确认的改地址草稿请求。";
                byte[] body = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
