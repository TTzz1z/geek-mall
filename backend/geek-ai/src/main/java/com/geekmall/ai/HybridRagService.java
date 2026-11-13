package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.web.InternalRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HybridRagService {
    private static final Logger log = LoggerFactory.getLogger(HybridRagService.class);
    private static final String PRODUCT = "PRODUCT";
    private static final String RULE = "RULE";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestClient es;
    private final EmbeddingClient embeddings;
    private final String index;
    private volatile boolean initialized;

    HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper, EmbeddingClient embeddings,
                     @Value("${geek.elasticsearch-url}") String esBase,
                     @Value("${geek.knowledge.index:geek-knowledge-v2}") String index) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.es = InternalRestClient.create(esBase);
        this.embeddings = embeddings;
        this.index = index;
    }

    public List<Citation> retrieve(String query, int topK) {
        return retrieve(query, topK, PRODUCT);
    }

    List<Citation> retrieve(String query, int topK, String scope) {
        return retrieve(query, topK, scope, false);
    }

    List<Citation> retrieveForEvaluation(String query, int topK, String scope) {
        return retrieve(query, topK, scope, true);
    }

    private List<Citation> retrieve(String query, int topK, String scope, boolean requireBackends) {
        if (query == null || query.isBlank() || topK < 1 || !(PRODUCT.equals(scope) || RULE.equals(scope))) return List.of();
        int k = Math.min(20, topK);
        int candidates = k * 4;
        var filters = new ArrayList<Map<String, Object>>();
        filters.add(Map.of("term", Map.of("kbCode", scope)));
        filters.add(Map.of("term", Map.of("scopeType", "PUBLIC")));
        var lexical = recall(Map.of("size", candidates, "_source", false,
                "query", Map.of("bool", Map.of("filter", filters, "must", Map.of("multi_match",
                        Map.of("query", query, "fields", List.of("title^2", "content")))))), requireBackends);
        List<Long> semantic = List.of();
        if (embeddings.enabled()) {
            try {
                var vectorFilters = new ArrayList<>(filters);
                vectorFilters.add(Map.of("term", Map.of("embeddingModel", embeddings.model())));
                semantic = recall(Map.of("size", candidates, "_source", false, "knn",
                        Map.of("field", "embedding", "query_vector", embeddings.query(query), "k", candidates,
                                "num_candidates", candidates * 4, "filter", vectorFilters, "similarity", 0.45)), requireBackends);
            } catch (RuntimeException e) {
                if (requireBackends) throw new BusinessException("AI50315", "语义检索不可用，无法完成评测");
                log.warn("Semantic recall unavailable ({})", e.getClass().getSimpleName());
            }
        }
        Map<Long, Double> ranking = reciprocalRanks(lexical, semantic);
        if (ranking.isEmpty()) return List.of();
        String placeholders = ranking.keySet().stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(ranking.keySet());
        parameters.add(scope);
        Map<Long, Citation> current = new HashMap<>();
        jdbc.query("""
                SELECT c.id,c.content,c.metadata_json,d.id AS document_id,d.title,d.source_type,d.source_id,d.version_no
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base kb ON kb.id=d.kb_id
                WHERE c.id IN (%s) AND kb.kb_code=? AND kb.scope_type='PUBLIC'
                    AND kb.status='ENABLED' AND d.parse_status='READY'
                """.formatted(placeholders), rs -> {
            long id = rs.getLong("id");
            current.put(id, new Citation(rs.getLong("document_id"), id, rs.getString("title"), rs.getString("source_type"),
                    rs.getString("source_id"), section(rs.getString("metadata_json")), rs.getString("version_no"),
                    rs.getString("content"), ranking.get(id), Map.of("scope", scope)));
        }, parameters.toArray());
        var citations = ranking.keySet().stream().filter(current::containsKey).map(current::get);
        if (requireBackends) {
            var seenSources = new HashSet<String>();
            citations = citations.filter(citation -> citation.sourceId() != null && seenSources.add(citation.sourceId()));
        }
        return citations.limit(k).toList();
    }

    String embeddingModel() {
        return embeddings.enabled() ? embeddings.model() : "LEXICAL";
    }

    boolean indexingComplete() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS(
                  SELECT 1 FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                  JOIN knowledge_base kb ON kb.id=d.kb_id
                  WHERE d.parse_status='READY' AND kb.status='ENABLED' AND kb.scope_type='PUBLIC'
                    AND (c.index_profile IS NULL OR c.index_profile<>?)
                )
                """, Boolean.class, indexProfile()));
    }

    private List<Long> recall(Map<String, Object> request, boolean requireBackend) {
        try {
            JsonNode result = es.post().uri("/{index}/_search", index).body(request).retrieve().body(JsonNode.class);
            if (result == null || !result.path("hits").path("hits").isArray()) {
                throw new IllegalStateException("invalid Elasticsearch response");
            }
            var ids = new ArrayList<Long>();
            for (JsonNode hit : result.path("hits").path("hits")) {
                ids.add(Long.parseLong(hit.path("_id").asText()));
            }
            return ids;
        } catch (RuntimeException e) {
            if (requireBackend) throw new BusinessException("AI50315", "Elasticsearch 不可用，无法完成评测");
            log.warn("Elasticsearch recall unavailable ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    static Map<Long, Double> reciprocalRanks(List<Long> lexical, List<Long> semantic) {
        Map<Long, Double> scores = new HashMap<>();
        for (List<Long> ranked : List.of(lexical, semantic)) {
            int rank = 1;
            for (Long id : ranked) scores.merge(id, 1.0 / (60 + rank++), Double::sum);
        }
        Map<Long, Double> sorted = new LinkedHashMap<>();
        scores.entrySet().stream().sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    @Scheduled(fixedDelayString = "${geek.knowledge.index-interval:5000}")
    void indexPending() {
        String profile = indexProfile();
        var rows = jdbc.queryForList("""
                SELECT c.id,c.content,d.title,k.kb_code,k.scope_type
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base k ON k.id=d.kb_id
                WHERE d.parse_status='READY' AND k.status='ENABLED' AND k.scope_type='PUBLIC'
                  AND (c.index_profile IS NULL OR c.index_profile<>?)
                  AND (c.index_retry_at IS NULL OR c.index_retry_at<=CURRENT_TIMESTAMP)
                ORDER BY c.id LIMIT 16
                """, profile);
        if (rows.isEmpty()) return;
        try {
            ensureIndex();
            List<List<Double>> vectors = embeddings.enabled()
                    ? embeddings.embed(rows.stream().map(row -> String.valueOf(row.get("content"))).toList()) : List.of();
            StringBuilder bulk = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                bulk.append(mapper.writeValueAsString(Map.of("index", Map.of("_id", row.get("id"))))).append('\n');
                var document = new LinkedHashMap<String, Object>();
                document.put("title", row.get("title"));
                document.put("content", row.get("content"));
                document.put("kbCode", row.get("kb_code"));
                document.put("scopeType", row.get("scope_type"));
                if (!vectors.isEmpty()) {
                    document.put("embeddingModel", embeddings.model());
                    document.put("embedding", vectors.get(i));
                }
                bulk.append(mapper.writeValueAsString(document)).append('\n');
            }
            JsonNode result = es.post().uri("/{index}/_bulk?refresh=wait_for", index)
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/x-ndjson"))
                    .body(bulk.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
            if (result == null || result.path("errors").asBoolean(true)) throw new IllegalStateException("bulk indexing failed");
            for (var row : rows) {
                jdbc.update("UPDATE knowledge_chunk SET index_profile=?,index_retry_at=NULL WHERE id=?", profile, row.get("id"));
            }
        } catch (Exception e) {
            initialized = false;
            for (var row : rows) {
                jdbc.update("UPDATE knowledge_chunk SET index_retry_at=TIMESTAMPADD(SECOND,15,CURRENT_TIMESTAMP) WHERE id=?", row.get("id"));
            }
            log.warn("Knowledge indexing will retry ({})", e.getClass().getSimpleName());
        }
    }

    private String indexProfile() {
        return index + "/" + embeddingModel();
    }

    private void ensureIndex() {
        if (initialized) return;
        try {
            es.head().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) throw e;
            es.put().uri("/{index}", index).body(Map.of("mappings", Map.of("properties", Map.of(
                    "title", Map.of("type", "text"),
                    "content", Map.of("type", "text"),
                    "kbCode", Map.of("type", "keyword"),
                    "scopeType", Map.of("type", "keyword"),
                    "embeddingModel", Map.of("type", "keyword"),
                    "embedding", Map.of("type", "dense_vector", "dims", EmbeddingClient.DIMENSIONS,
                            "index", true, "similarity", "cosine"))))).retrieve().toBodilessEntity();
        }
        initialized = true;
    }

    private String section(String metadata) {
        try {
            return mapper.readTree(metadata).path("section").asText("正文");
        } catch (Exception e) {
            return "正文";
        }
    }

    public String buildEvidence(List<Citation> citations) {
        if (citations.isEmpty()) return "未检索到足够证据，请勿编造参数。";
        StringBuilder text = new StringBuilder();
        for (Citation c : citations) {
            text.append(c.documentTitle()).append(" / ").append(c.section()).append(" v")
                    .append(c.versionNo()).append('\n').append(c.snippet()).append("\n\n");
        }
        return text.toString();
    }

    public record Citation(long documentId, long chunkId, String documentTitle, String sourceType, String sourceId,
                           String section, String versionNo, String snippet, double score, Map<String, Object> debug) {
        public Map<String, Object> asMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("documentId", documentId);
            result.put("chunkId", chunkId);
            result.put("documentTitle", documentTitle);
            result.put("sourceType", sourceType);
            result.put("sourceId", sourceId);
            result.put("section", section);
            result.put("version", versionNo);
            result.put("scope", debug.get("scope"));
            result.put("snippet", snippet);
            result.put("score", score);
            return result;
        }
    }
}
