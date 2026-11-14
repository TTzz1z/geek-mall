package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.web.InternalRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
class EmbeddingClient {
    static final int DIMENSIONS = 1024;
    private final RestClient client;
    private final RestClient indexingClient;
    private final String model;
    private final String apiKey;
    private final boolean enabled;

    EmbeddingClient(@Value("${geek.knowledge.embedding-url:}") String url,
                    @Value("${geek.knowledge.embedding-model:BAAI/bge-m3}") String model,
                    @Value("${geek.knowledge.embedding-key:}") String apiKey) {
        this.enabled = !url.isBlank();
        String baseUrl = url.replaceAll("/+$", "");
        this.client = enabled ? InternalRestClient.create(baseUrl) : null;
        this.indexingClient = enabled ? InternalRestClient.create(baseUrl, Duration.ofSeconds(60)) : null;
        this.model = model;
        this.apiKey = apiKey;
    }

    String model() {
        return model;
    }

    boolean enabled() {
        return enabled;
    }

    List<Double> query(String text) {
        String input = model.toLowerCase(java.util.Locale.ROOT).contains("qwen3")
                ? "Instruct: Given a question, retrieve relevant product or policy documents\nQuery: " + text : text;
        return embed(client, List.of(input)).getFirst();
    }

    List<List<Double>> embed(List<String> inputs) {
        return embed(indexingClient, inputs);
    }

    private List<List<Double>> embed(RestClient client, List<String> inputs) {
        if (!enabled) throw new IllegalStateException("embedding endpoint is not configured");
        var request = client.post().uri("/embeddings").body(Map.of("model", model, "input", inputs));
        if (!apiKey.isBlank()) request.headers(headers -> headers.setBearerAuth(apiKey));
        JsonNode body = request.retrieve().body(JsonNode.class);
        if (body == null || body.path("data").size() != inputs.size()) throw new IllegalStateException("incomplete embedding response");
        List<List<Double>> result = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        for (JsonNode item : body.path("data")) {
            int index = item.path("index").asInt(-1);
            JsonNode vector = item.path("embedding");
            if (index < 0 || index >= inputs.size() || result.get(index) != null || vector.size() != DIMENSIONS) {
                throw new IllegalStateException("invalid embedding shape");
            }
            var values = new ArrayList<Double>(DIMENSIONS);
            boolean nonzero = false;
            for (JsonNode value : vector) {
                double number = value.asDouble(Double.NaN);
                if (!value.isNumber() || !Double.isFinite(number) || Math.abs(number) > Float.MAX_VALUE) {
                    throw new IllegalStateException("invalid embedding value");
                }
                nonzero |= number != 0;
                values.add(number);
            }
            if (!nonzero) throw new IllegalStateException("empty embedding");
            result.set(index, List.copyOf(values));
        }
        return result;
    }
}
