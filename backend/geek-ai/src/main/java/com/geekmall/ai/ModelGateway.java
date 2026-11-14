package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.concurrent.CancellationException;

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

    CompletionResult completeAnswer(String systemPrompt, String userPrompt, Long conversationId, AnswerStreamGuard output) {
        var result = completeAnswer(systemPrompt, userPrompt, conversationId);
        if (result.degraded()) return result;
        try {
            output.accept(result.text());
            output.finish();
            return result;
        } catch (IllegalArgumentException unsafe) {
            return new CompletionResult(result.modelName(), localComplete(), result.promptTokens(),
                    result.completionTokens(), result.tokenUsageEstimated(), true);
        }
    }

    CompletionResult streamAnswer(String systemPrompt, String userPrompt, Long conversationId,
                                  AnswerStreamGuard output, TurnCancellation cancellation) {
        if (!remoteConfigured() || failPrimary) return completeAnswer(systemPrompt, userPrompt, conversationId);
        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        try {
            var messages = List.of(Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt));
            var body = Map.of("model", llmModel, "messages", messages, "temperature", 0.3,
                    "stream", true, "stream_options", Map.of("include_usage", true));
            var request = HttpRequest.newBuilder(chatEndpoint())
                    .timeout(Duration.ofMillis(llmTimeoutMs)).header("Authorization", "Bearer " + llmApiKey)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            var responseBody = response.body();
            try (var input = responseBody; var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                cancellation.attach(input);
                if (response.statusCode() != 200) throw new IllegalStateException("upstream rejected stream");
                var answer = new StringBuilder();
                JsonNode providerUsage = null;
                boolean finished = false;
                for (String line; (line = readEventLine(reader)) != null;) {
                    cancellation.check();
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).strip();
                    if ("[DONE]".equals(data)) {
                        finished = true;
                        break;
                    }
                    JsonNode event = mapper.readTree(data);
                    if (event.hasNonNull("usage")) providerUsage = event.path("usage");
                    String delta = event.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        if (answer.length() + delta.length() > 32_000) throw new IllegalStateException("upstream answer too long");
                        answer.append(delta);
                        output.accept(delta);
                    }
                }
                cancellation.check();
                if (!finished || answer.isEmpty()) throw new IllegalStateException("incomplete upstream stream");
                output.finish();
                String text = answer.toString();
                var remote = providerUsage == null ? null : new RemoteCompletion(text, providerUsage.path("prompt_tokens").asInt(),
                        providerUsage.path("completion_tokens").asInt(), providerUsage.has("prompt_tokens") && providerUsage.has("completion_tokens"));
                var usage = tokenUsage(systemPrompt, userPrompt, text, remote);
                log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - started), usage.promptTokens(),
                        usage.completionTokens(), usage.estimated(), "SUCCESS", "scene=ANSWER;stream");
                return new CompletionResult(primary, text, usage.promptTokens(), usage.completionTokens(), usage.estimated(), false);
            } finally {
                cancellation.detach(responseBody);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (Exception e) {
            cancellation.check();
            if (e instanceof CancellationException cancelled) throw cancelled;
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - started),
                    estimateTokens(systemPrompt + userPrompt), 0, true, "FAILED", "scene=ANSWER;stream");
            return new CompletionResult(fallback, localComplete(), estimateTokens(systemPrompt + userPrompt), 0, true, true);
        }
    }

    private static String readEventLine(BufferedReader reader) throws java.io.IOException {
        var line = new StringBuilder();
        for (int ch; (ch = reader.read()) != -1;) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 65_536) throw new java.io.IOException("upstream event too long");
            line.append((char) ch);
        }
        return line.isEmpty() ? null : line.toString();
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
            RemoteCompletion remote = null;
            String text;
            if (!remoteConfigured()) {
                text = localComplete();
            } else {
                remote = openAiCompatibleChat(systemPrompt, userPrompt, temperature, llmTimeoutMs, false);
                text = remote.text();
            }
            TokenUsage usage = tokenUsage(systemPrompt, userPrompt, text, remote);
            log(requestId, conversationId, model, (int) (System.currentTimeMillis() - start),
                    usage.promptTokens(), usage.completionTokens(), usage.estimated(), "SUCCESS",
                    remoteConfigured() ? ("scene=" + scene) : "local");
            return new CompletionResult(model, text, usage.promptTokens(), usage.completionTokens(), usage.estimated(), !remoteConfigured());
        } catch (Exception primaryError) {
            model = fallback;
            String text = localComplete();
            int promptTokens = estimateTokens(systemPrompt + userPrompt);
            int completionTokens = estimateTokens(text);
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    promptTokens, 0, true, "FAILED", "scene=" + scene + ";" + primaryError.getClass().getSimpleName());
            log(requestId + "-fb", conversationId, model, (int) (System.currentTimeMillis() - start),
                    promptTokens, completionTokens, true, "SUCCESS", "scene=" + scene + ";fallback");
            return new CompletionResult(model, text, promptTokens, completionTokens, true, true);
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
            RemoteCompletion remote = openAiCompatibleChat(systemPrompt, userPrompt, temperature, timeoutMs, true);
            TokenUsage usage = tokenUsage(systemPrompt, userPrompt, remote.text(), remote);
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    usage.promptTokens(), usage.completionTokens(), usage.estimated(), "SUCCESS", "scene=" + scene);
            return new CompletionResult(primary, remote.text(), usage.promptTokens(), usage.completionTokens(), usage.estimated(), false);
        } catch (Exception ex) {
            log(requestId, conversationId, primary, (int) (System.currentTimeMillis() - start),
                    estimateTokens(systemPrompt + userPrompt), 0, true, "FAILED",
                    "scene=" + scene + ";" + ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private RemoteCompletion openAiCompatibleChat(String systemPrompt, String userPrompt,
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
                .uri(chatEndpoint())
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
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("LLM empty content");
        }
        JsonNode usage = root.path("usage");
        boolean hasUsage = usage.isObject() && usage.has("prompt_tokens") && usage.has("completion_tokens");
        return new RemoteCompletion(content.asText(), usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt(), hasUsage);
    }

    private String localComplete() {
        return "模型暂时不可用，请稍后重试。";
    }

    private URI chatEndpoint() {
        return URI.create(llmBaseUrl + (llmBaseUrl.endsWith("/v1") ? "" : "/v1") + "/chat/completions");
    }

    private void log(String requestId, Long conversationId, String model, int latency,
                     int promptTokens, int completionTokens, boolean usageEstimated, String status, String detail) {
        double cost = (promptTokens + completionTokens) * 0.000002;
        jdbc.update("""
                INSERT INTO ai_model_call_log(id,request_id,conversation_id,model_name,latency_ms,prompt_tokens,completion_tokens,cost,usage_estimated,status,detail,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, com.geekmall.common.domain.DistributedId.next(), requestId, conversationId, model, latency,
                promptTokens, completionTokens, cost, usageEstimated, status,
                (detail == null ? "" : detail) + ";usage=" + (usageEstimated ? "estimated" : "actual"), Timestamp.from(Instant.now()));
    }

    private TokenUsage tokenUsage(String systemPrompt, String userPrompt, String text, RemoteCompletion remote) {
        if (remote != null && remote.hasUsage()) {
            return new TokenUsage(remote.promptTokens(), remote.completionTokens(), false);
        }
        return new TokenUsage(estimateTokens(systemPrompt + userPrompt), estimateTokens(text), true);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 2);
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.deepseek.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record RemoteCompletion(String text, int promptTokens, int completionTokens, boolean hasUsage) {}
    private record TokenUsage(int promptTokens, int completionTokens, boolean estimated) {}

    public record CompletionResult(String modelName, String text, int promptTokens, int completionTokens,
                                   boolean tokenUsageEstimated, boolean degraded) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelName", modelName);
            m.put("promptTokens", promptTokens);
            m.put("completionTokens", completionTokens);
            m.put("estimated", tokenUsageEstimated);
            m.put("tokenUsageEstimated", tokenUsageEstimated);
            m.put("tokenUsageSource", tokenUsageEstimated ? "ESTIMATED" : "PROVIDER_USAGE");
            m.put("degraded", degraded);
            return m;
        }
    }
}
