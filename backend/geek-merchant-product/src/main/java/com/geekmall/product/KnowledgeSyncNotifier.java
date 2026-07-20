package com.geekmall.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
class KnowledgeSyncNotifier {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncNotifier.class);
    private final ObjectMapper mapper;
    private final String aiBaseUrl;
    private final String internalKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    KnowledgeSyncNotifier(ObjectMapper mapper,
                          @Value("${geek.ai-service-url:http://127.0.0.1:9011}") String aiBaseUrl,
                          @Value("${geek.internal-key}") String internalKey) {
        this.mapper = mapper;
        this.aiBaseUrl = aiBaseUrl.endsWith("/") ? aiBaseUrl.substring(0, aiBaseUrl.length() - 1) : aiBaseUrl;
        this.internalKey = internalKey;
    }

    void sync(Map<String, Object> product, String action) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "action", action,
                    "product", product == null ? Map.of() : product
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(aiBaseUrl + "/api/v1/internal/ai/knowledge/product-sync"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 300) {
                log.warn("knowledge sync failed status={} body={}", resp.statusCode(), resp.body());
            }
        } catch (Exception ex) {
            log.warn("knowledge sync skipped: {}", ex.toString());
        }
    }
}
