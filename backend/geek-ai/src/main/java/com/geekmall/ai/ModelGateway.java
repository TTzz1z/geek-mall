package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String primary;
    private final String fallback;
    private final boolean failPrimary;
    private final boolean llmEnabled;
    private final String llmBaseUrl;
    private final String llmApiKey;
    private final String llmModel;
    private final int llmTimeoutMs;

    public ModelGateway(JdbcTemplate jdbc,
                        ObjectMapper mapper,
                        @Value("${geek.model.primary}") String primary,
                        @Value("${geek.model.fallback}") String fallback,
                        @Value("${geek.model.fail-primary:false}") boolean failPrimary,
                        @Value("${geek.llm.enabled:true}") boolean llmEnabled,
                        @Value("${geek.llm.base-url:https://api.deepseek.com}") String llmBaseUrl,
                        @Value("${geek.llm.api-key:}") String llmApiKey,
                        @Value("${geek.llm.model:deepseek-v4-flash}") String llmModel,
                        @Value("${geek.llm.timeout-ms:60000}") int llmTimeoutMs) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.primary = primary;
        this.fallback = fallback;
        this.failPrimary = failPrimary;
        this.llmEnabled = llmEnabled;
        this.llmBaseUrl = trimSlash(llmBaseUrl);
        this.llmApiKey = llmApiKey == null ? "" : llmApiKey.trim();
        this.llmModel = llmModel;
        this.llmTimeoutMs = llmTimeoutMs;
    }

    public CompletionResult complete(String scene, String systemPrompt, String userPrompt, Long conversationId) {
        return complete(scene, systemPrompt, userPrompt, conversationId, 0.3);
    }

    /** Phase C answer synthesis (temperature ~0.3). */
    public CompletionResult completeAnswer(String systemPrompt, String userPrompt, Long conversationId) {
        return complete("ANSWER", systemPrompt, userPrompt, conversationId, 0.3);
    }

    public CompletionResult complete(String scene, String systemPrompt, String userPrompt,
                                     Long conversationId, double temperature) {
        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String model = primary;
        try {
            if (failPrimary) {
                throw new IllegalStateException("primary model unavailable");
            }
            String text;
            if (!remoteConfigured()) {
                text = localComplete(systemPrompt, userPrompt);
            } else {
                text = openAiCompatibleChat(systemPrompt, userPrompt, temperature, llmTimeoutMs, false);
            }
            int promptTokens = estimateTokens(systemPrompt + userPrompt);
            int completionTokens = estimateTokens(text);
            log(requestId, conversationId, model, (int) (System.currentTimeMillis() - start),
                    promptTokens, completionTokens, "SUCCESS",
                    remoteConfigured() ? ("scene=" + scene) : "local");
            return new CompletionResult(model, text, promptTokens, completionTokens, false);
        } catch (Exception primaryError) {
            model = fallback;
            String text = localComplete(systemPrompt, userPrompt) + "\n\n（已降级至 " + fallback + "）";
            int promptTokens = estimateTokens(systemPrompt + userPrompt);
            int completionTokens = estimateTokens(text);
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    promptTokens, 0, "FAILED", "scene=" + scene + ";" + primaryError.getMessage());
            log(requestId + "-fb", conversationId, model, (int) (System.currentTimeMillis() - start),
                    promptTokens, completionTokens, "SUCCESS", "scene=" + scene + ";fallback");
            return new CompletionResult(model, text, promptTokens, completionTokens, true);
        }
    }

    public boolean remoteConfigured() {
        return llmEnabled && !llmApiKey.isBlank();
    }

    /** Low-temperature JSON completion for planner; throws if remote LLM unavailable. */
    public CompletionResult completeJson(String scene, String systemPrompt, String userPrompt,
                                         Long conversationId, double temperature, int timeoutMs) throws Exception {
        if (!remoteConfigured()) {
            throw new IllegalStateException("LLM not configured");
        }
        if (failPrimary) {
            throw new IllegalStateException("primary model unavailable");
        }
        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        try {
            String text = openAiCompatibleChat(systemPrompt, userPrompt, temperature, timeoutMs, true);
            int promptTokens = estimateTokens(systemPrompt + userPrompt);
            int completionTokens = estimateTokens(text);
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    promptTokens, completionTokens, "SUCCESS", "scene=" + scene);
            return new CompletionResult(primary, text, promptTokens, completionTokens, false);
        } catch (Exception ex) {
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    estimateTokens(systemPrompt + userPrompt), 0, "FAILED",
                    "scene=" + scene + ";" + (ex.getMessage() == null ? "planner-failed" : ex.getMessage()));
            throw ex;
        }
    }

    private String openAiCompatibleChat(String systemPrompt, String userPrompt,
                                        double temperature, int timeoutMs, boolean jsonObject) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llmModel);
        body.put("messages", messages);
        body.put("temperature", temperature);
        if (jsonObject) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        byte[] payload = mapper.writeValueAsBytes(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(llmBaseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                .header("Authorization", "Bearer " + llmApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Some providers reject response_format — retry once without it for planner.
            if (jsonObject && response.statusCode() == 400) {
                return openAiCompatibleChat(systemPrompt, userPrompt, temperature, timeoutMs, false);
            }
            throw new IllegalStateException("LLM HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("LLM empty content: " + truncate(response.body()));
        }
        return content.asText();
    }

    private String localComplete(String systemPrompt, String userPrompt) {
        return "[模型回复·本地降级]\n" + (userPrompt == null ? "" : userPrompt);
    }

    private void log(String requestId, Long conversationId, String model, int latency,
                     int promptTokens, int completionTokens, String status, String detail) {
        double cost = (promptTokens + completionTokens) * 0.000002;
        jdbc.update("""
                INSERT INTO ai_model_call_log(id,request_id,conversation_id,model_name,latency_ms,prompt_tokens,completion_tokens,cost,status,detail,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, com.geekmall.common.domain.DistributedId.next(), requestId, conversationId, model, latency,
                promptTokens, completionTokens, cost, status, detail, Timestamp.from(Instant.now()));
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 2);
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.deepseek.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400);
    }

    public record CompletionResult(String modelName, String text, int promptTokens, int completionTokens, boolean degraded) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelName", modelName);
            m.put("promptTokens", promptTokens);
            m.put("completionTokens", completionTokens);
            m.put("degraded", degraded);
            return m;
        }
    }
}
