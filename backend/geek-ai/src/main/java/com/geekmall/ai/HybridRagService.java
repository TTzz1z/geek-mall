package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.web.InternalRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final double minimumSimilarity;
    private final TransactionTemplate tx;

    HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper, EmbeddingClient embeddings,
                     String esBase, String index) {
        this(jdbc, mapper, embeddings, esBase, index, 0);
    }

    @Autowired
    HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper, EmbeddingClient embeddings,
                     @Value("${geek.elasticsearch-url}") String esBase,
                     @Value("${geek.knowledge.index:geek-knowledge-v2}") String index,
                     @Value("${geek.knowledge.minimum-similarity:0.62}") double minimumSimilarity,
                     @Value("${geek.knowledge.require-semantic-gate:false}") boolean requireSemanticGate) {
        this(jdbc, mapper, embeddings, esBase, index, embeddings.enabled() || requireSemanticGate ? minimumSimilarity : 0);
        if (requireSemanticGate && minimumSimilarity <= 0) throw new IllegalArgumentException("required semantic gate must have a positive threshold");
    }

    HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper, EmbeddingClient embeddings,
                     String esBase, String index, double minimumSimilarity) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.es = InternalRestClient.create(esBase);
        this.embeddings = embeddings;
        this.index = index;
        if (!Double.isFinite(minimumSimilarity) || minimumSimilarity < 0 || minimumSimilarity > 1) {
            throw new IllegalArgumentException("minimum similarity must be between 0 and 1");
        }
        this.minimumSimilarity = minimumSimilarity;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
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
        if (minimumSimilarity > 0 && !embeddings.enabled()) {
            if (requireBackends) throw new BusinessException("AI50315", "语义证据门禁未配置真实 embedding 后端");
            return List.of();
        }
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
                                "num_candidates", candidates * 4, "filter", vectorFilters,
                                "similarity", minimumSimilarity > 0 ? minimumSimilarity : 0.45)), requireBackends);
            } catch (RuntimeException e) {
                if (requireBackends) throw new BusinessException("AI50315", "语义检索不可用，无法完成评测");
                log.warn("Semantic recall unavailable ({})", e.getClass().getSimpleName());
            }
        }
        Map<Long, Double> ranking = reciprocalRanks(lexical, semantic);
        if (minimumSimilarity > 0) {
            var admitted = new HashSet<>(semantic);
            ranking.keySet().removeIf(id -> !admitted.contains(id));
        }
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
                    AND kb.status='ENABLED' AND d.parse_status='READY' AND d.activation_status='ACTIVE'
                """.formatted(placeholders), rs -> {
            long id = rs.getLong("id");
            current.put(id, new Citation(rs.getLong("document_id"), id, rs.getString("title"), rs.getString("source_type"),
                    rs.getString("source_id"), section(rs.getString("metadata_json")), rs.getString("version_no"),
                    rs.getString("content"), ranking.get(id), Map.of("scope", scope)));
        }, parameters.toArray());
        var seenSources = new HashSet<String>();
        return ranking.keySet().stream().filter(current::containsKey).map(current::get)
                .filter(citation -> citation.sourceId() != null && seenSources.add(citation.sourceId()))
                .limit(k).toList();
    }

    String embeddingModel() {
        return embeddings.enabled() ? embeddings.model() : "LEXICAL";
    }

    double minimumSimilarity() { return minimumSimilarity; }

    boolean indexingComplete() {
        // A successful SQL marker alone cannot prove that Elasticsearch still exists.
        try {
            es.head().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            return false;
        }
        var states = jdbc.queryForList("SELECT generation,model_profile FROM knowledge_index_state WHERE index_name=?", index);
        if (states.isEmpty() || !embeddingModel().equals(states.getFirst().get("model_profile"))) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS(
                  SELECT 1 FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                  JOIN knowledge_base kb ON kb.id=d.kb_id
                  WHERE d.parse_status='READY' AND d.activation_status IN ('ACTIVE','CANDIDATE')
                    AND kb.status='ENABLED' AND kb.scope_type='PUBLIC'
                    AND (c.index_profile IS NULL OR c.index_profile<>?)
                )
                """, Boolean.class, profile(((Number) states.getFirst().get("generation")).longValue())));
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
    synchronized void indexPending() {
        List<Map<String, Object>> rows = List.of();
        try {
            // Probe before selecting work: a deleted ES index must reset already-successful SQL tasks.
            String profile = ensureIndex();
            cleanupRetired();
            rows = jdbc.queryForList("""
                SELECT c.id,c.content,d.id AS document_id,d.title,k.kb_code,k.scope_type
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base k ON k.id=d.kb_id
                WHERE d.parse_status='READY' AND k.status='ENABLED' AND k.scope_type='PUBLIC'
                  AND d.activation_status IN ('ACTIVE','CANDIDATE')
                  AND (c.index_profile IS NULL OR c.index_profile<>?)
                  AND (c.index_retry_at IS NULL OR c.index_retry_at<=CURRENT_TIMESTAMP)
                ORDER BY c.id LIMIT 16
                """, profile);
            List<List<Double>> vectors = embeddings.enabled()
                    && !rows.isEmpty() ? embeddings.embed(rows.stream().map(row -> String.valueOf(row.get("content"))).toList()) : List.of();
            StringBuilder bulk = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                bulk.append(mapper.writeValueAsString(Map.of("index", Map.of("_id", row.get("id"))))).append('\n');
                var document = new LinkedHashMap<String, Object>();
                document.put("title", row.get("title"));
                document.put("content", row.get("content"));
                document.put("kbCode", row.get("kb_code"));
                document.put("scopeType", row.get("scope_type"));
                document.put("documentId", row.get("document_id"));
                if (!vectors.isEmpty()) {
                    document.put("embeddingModel", embeddings.model());
                    document.put("embedding", vectors.get(i));
                }
                bulk.append(mapper.writeValueAsString(document)).append('\n');
            }
            if (!rows.isEmpty()) {
                JsonNode result = es.post().uri("/{index}/_bulk?refresh=wait_for", index)
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/x-ndjson"))
                    .body(bulk.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
                if (result == null || result.path("errors").asBoolean(true)) throw new IllegalStateException("bulk indexing failed");
            }
            List<Map<String, Object>> indexed = rows;
            tx.executeWithoutResult(status -> {
                var state = lockState();
                if (!profile.equals(profile(((Number) state.get("generation")).longValue()))) return;
                for (var row : indexed) {
                    jdbc.update("UPDATE knowledge_chunk SET index_profile=?,index_retry_at=NULL WHERE id=?", profile, row.get("id"));
                    // Off-sale can race an in-flight bulk write. Keep its cleanup retry durable.
                    jdbc.update("""
                            UPDATE knowledge_document SET index_cleanup_pending=TRUE WHERE id=?
                            AND activation_status IN ('SUPERSEDED','INACTIVE')
                            """, row.get("document_id"));
                }
                activateCompletedCandidates(profile);
                jdbc.update("""
                        UPDATE knowledge_index_state SET status=?,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE index_name=?
                        """, pendingCount(profile) == 0 ? "READY" : "BUILDING", index);
            });
            cleanupRetired();
        } catch (Exception e) {
            for (var row : rows) {
                jdbc.update("UPDATE knowledge_chunk SET index_retry_at=TIMESTAMPADD(SECOND,15,CURRENT_TIMESTAMP) WHERE id=?", row.get("id"));
            }
            jdbc.update("""
                    UPDATE knowledge_index_state SET status='RETRYING',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE index_name=?
                    """, e.getClass().getSimpleName(), index);
            log.warn("Knowledge indexing will retry ({})", e.getClass().getSimpleName());
        }
    }

    public synchronized Map<String, Object> rebuildIndex() {
        registerState();
        tx.executeWithoutResult(status -> {
            lockState();
            resetGeneration();
        });
        return indexStatus();
    }

    public synchronized Map<String, Object> requestActivation(long documentId) {
        return tx.execute(status -> {
            var docs = jdbc.queryForList("SELECT * FROM knowledge_document WHERE id=?", documentId);
            if (docs.isEmpty()) throw new BusinessException("AI40405", "知识文档不存在");
            var doc = docs.getFirst();
            jdbc.queryForObject("SELECT id FROM knowledge_base WHERE id=? FOR UPDATE", Long.class, doc.get("kb_id"));
            doc = jdbc.queryForMap("SELECT * FROM knowledge_document WHERE id=? FOR UPDATE", documentId);
            if (!"READY".equals(doc.get("parse_status"))) throw new BusinessException("AI40905", "仅可激活解析完成且未下架的文档");
            if ("ACTIVE".equals(doc.get("activation_status"))) {
                return Map.of("documentId", documentId, "activationStatus", "ACTIVE", "indexStatus", "CURRENT");
            }
            if ("CANDIDATE".equals(doc.get("activation_status"))) {
                return Map.of("documentId", documentId, "activationStatus", "CANDIDATE", "indexStatus", "PENDING");
            }
            jdbc.update("""
                    UPDATE knowledge_document SET activation_status='SUPERSEDED',index_cleanup_pending=TRUE
                    WHERE kb_id=? AND source_type=? AND source_id=? AND activation_status='CANDIDATE' AND id<>?
                    """, doc.get("kb_id"), doc.get("source_type"), doc.get("source_id"), documentId);
            jdbc.update("UPDATE knowledge_document SET activation_status='CANDIDATE',index_cleanup_pending=FALSE WHERE id=?", documentId);
            jdbc.update("UPDATE knowledge_chunk SET index_profile=NULL,index_retry_at=NULL WHERE document_id=?", documentId);
            return Map.of("documentId", documentId, "activationStatus", "CANDIDATE", "indexStatus", "PENDING");
        });
    }

    public Map<String, Object> indexStatus() {
        var rows = jdbc.queryForList("SELECT * FROM knowledge_index_state WHERE index_name=?", index);
        if (rows.isEmpty()) return Map.of("index", index, "status", "NOT_STARTED", "model", embeddingModel());
        var state = rows.getFirst();
        long generation = ((Number) state.get("generation")).longValue();
        var result = new LinkedHashMap<String, Object>();
        result.put("index", index);
        result.put("generation", generation);
        result.put("model", state.get("model_profile"));
        result.put("status", state.get("status"));
        result.put("pendingChunks", pendingCount(profile(generation)));
        result.put("lastError", state.get("last_error"));
        return result;
    }

    private String profile(long generation) {
        return index + "/" + embeddingModel() + "/" + generation;
    }

    private void registerState() {
        jdbc.update("""
                INSERT INTO knowledge_index_state(index_name,generation,model_profile,status,updated_at)
                VALUES(?,0,?,'BUILDING',CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE index_name=index_name
                """, index, embeddingModel());
    }

    private Map<String, Object> lockState() {
        return jdbc.queryForMap("SELECT * FROM knowledge_index_state WHERE index_name=? FOR UPDATE", index);
    }

    private void resetGeneration() {
        jdbc.update("""
                UPDATE knowledge_index_state SET generation=generation+1,model_profile=?,status='BUILDING',
                    last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE index_name=?
                """, embeddingModel(), index);
        jdbc.update("""
                UPDATE knowledge_chunk SET index_profile=NULL,index_retry_at=NULL
                WHERE document_id IN (SELECT id FROM knowledge_document WHERE parse_status='READY'
                    AND activation_status IN ('ACTIVE','CANDIDATE'))
                """);
    }

    private String ensureIndex() {
        registerState();
        boolean missing = false;
        try {
            es.head().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) throw e;
            missing = true;
        }
        boolean recreate = missing;
        // Invalidate SQL success durably before any external creation. A lost PUT response or
        // process exit must leave retryable chunks even when ES already committed the empty index.
        String currentProfile = tx.execute(status -> {
            var state = lockState();
            if (recreate || !embeddingModel().equals(state.get("model_profile"))
                    || ((Number) state.get("generation")).longValue() == 0) resetGeneration();
            return profile(((Number) lockState().get("generation")).longValue());
        });
        if (recreate) {
            try {
                es.put().uri("/{index}", index).body(Map.of("mappings", Map.of("properties", Map.of(
                    "title", Map.of("type", "text"),
                    "content", Map.of("type", "text"),
                    "kbCode", Map.of("type", "keyword"),
                    "scopeType", Map.of("type", "keyword"),
                    "documentId", Map.of("type", "long"),
                    "embeddingModel", Map.of("type", "keyword"),
                    "embedding", Map.of("type", "dense_vector", "dims", EmbeddingClient.DIMENSIONS,
                            "index", true, "similarity", "cosine"))))).retrieve().toBodilessEntity();
            } catch (RestClientResponseException e) {
                // Another creator may have won after the HEAD probe. All other failures retry.
                if (e.getStatusCode().value() != 400
                        || !e.getResponseBodyAsString().contains("resource_already_exists_exception")) throw e;
            }
        }
        return currentProfile;
    }

    private long pendingCount(String profile) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base k ON k.id=d.kb_id
                WHERE d.parse_status='READY' AND d.activation_status IN ('ACTIVE','CANDIDATE')
                  AND k.status='ENABLED' AND k.scope_type='PUBLIC'
                  AND (c.index_profile IS NULL OR c.index_profile<>?)
                """, Long.class, profile);
    }

    private void activateCompletedCandidates(String profile) {
        var candidates = jdbc.queryForList("""
                SELECT d.id,d.kb_id,d.source_type,d.source_id FROM knowledge_document d
                JOIN knowledge_base k ON k.id=d.kb_id
                WHERE d.parse_status='READY' AND d.activation_status='CANDIDATE'
                  AND k.status='ENABLED' AND k.scope_type='PUBLIC'
                  AND EXISTS(SELECT 1 FROM knowledge_chunk c WHERE c.document_id=d.id)
                  AND NOT EXISTS(SELECT 1 FROM knowledge_chunk c WHERE c.document_id=d.id
                      AND (c.index_profile IS NULL OR c.index_profile<>?)) ORDER BY d.id
                """, profile);
        for (var doc : candidates) {
            jdbc.queryForObject("SELECT id FROM knowledge_base WHERE id=? FOR UPDATE", Long.class, doc.get("kb_id"));
            // Ingest and off-sale share this KB lock; recheck after acquiring it.
            if (!"CANDIDATE".equals(jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=?",
                    String.class, doc.get("id")))) continue;
            jdbc.update("""
                    UPDATE knowledge_document SET activation_status='SUPERSEDED',index_cleanup_pending=TRUE
                    WHERE kb_id=? AND source_type=? AND source_id=? AND activation_status='ACTIVE'
                    """, doc.get("kb_id"), doc.get("source_type"), doc.get("source_id"));
            jdbc.update("UPDATE knowledge_document SET activation_status='ACTIVE' WHERE id=?", doc.get("id"));
        }
    }

    private void cleanupRetired() {
        for (var doc : jdbc.queryForList("""
                SELECT id FROM knowledge_document WHERE index_cleanup_pending=TRUE
                  AND activation_status IN ('SUPERSEDED','INACTIVE') ORDER BY id LIMIT 16
                """)) {
            var ids = jdbc.queryForList("SELECT id FROM knowledge_chunk WHERE document_id=?", String.class, doc.get("id"));
            es.post().uri("/{index}/_delete_by_query?refresh=true", index)
                    .body(Map.of("query", Map.of("bool", Map.of("should", List.of(
                            Map.of("term", Map.of("documentId", doc.get("id"))),
                            Map.of("terms", Map.of("_id", ids))), "minimum_should_match", 1))))
                    .retrieve().toBodilessEntity();
            jdbc.update("UPDATE knowledge_document SET index_cleanup_pending=FALSE WHERE id=?", doc.get("id"));
        }
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
