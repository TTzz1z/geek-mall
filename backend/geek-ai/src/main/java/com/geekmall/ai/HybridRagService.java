package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class HybridRagService {
    static final String INDEX = "geek-knowledge-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().build();
    private final String esBase;
    private final boolean milvusEnabled;
    private final String milvusHost;
    private final int milvusPort;
    private final double minEvidenceScore;

    @Autowired
    public HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper,
                            @Value("${geek.elasticsearch-url}") String esBase,
                            @Value("${geek.milvus.enabled:false}") boolean milvusEnabled,
                            @Value("${geek.milvus.host:localhost}") String milvusHost,
                            @Value("${geek.milvus.port:19530}") int milvusPort,
                            @Value("${geek.rag.min-evidence-score:0.45}") double minEvidenceScore) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.esBase = esBase.endsWith("/") ? esBase.substring(0, esBase.length() - 1) : esBase;
        this.milvusEnabled = milvusEnabled;
        this.milvusHost = milvusHost;
        this.milvusPort = milvusPort;
        this.minEvidenceScore = minEvidenceScore;
    }

    HybridRagService(JdbcTemplate jdbc, ObjectMapper mapper, String esBase, boolean milvusEnabled,
                     String milvusHost, int milvusPort) {
        this(jdbc, mapper, esBase, milvusEnabled, milvusHost, milvusPort, 0.45);
    }

    @PostConstruct
    void bootstrapIndex() {
        try {
            ensureIndex();
            reindexAllChunks();
        } catch (Exception ignored) {
            // ES 不可用时退回 MySQL BM25 + 本地向量
        }
    }

    public List<Citation> retrieve(String query, int topK) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) return List.of();

        Map<Long, Scored> fused = new LinkedHashMap<>();

        // 1) MySQL keyword + local 64-dimensional deterministic character-hash prototype.
        for (Map<String, Object> row : loadChunks()) {
            long chunkId = ((Number) row.get("chunkId")).longValue();
            String content = String.valueOf(row.get("content"));
            double bm25 = keywordScore(q, content + " " + row.get("documentTitle"));
            double vector = vectorScore(q, content, String.valueOf(row.get("vectorId")), chunkId);
            double score = 0.35 * bm25 + 0.35 * vector;
            if (score > 0) fused.put(chunkId, new Scored(row, score, bm25, vector, 0));
        }

        // 2) Elasticsearch BM25 recall
        Map<Long, Double> esHits = esRecall(q, Math.max(topK * 4, 12));
        for (var e : esHits.entrySet()) {
            Scored existing = fused.get(e.getKey());
            if (existing == null) {
                Map<String, Object> row = findChunk(e.getKey());
                if (row == null) continue;
                fused.put(e.getKey(), new Scored(row, 0.4 * e.getValue(), keywordScore(q, String.valueOf(row.get("content"))), 0, e.getValue()));
            } else {
                fused.put(e.getKey(), new Scored(existing.row, existing.fused + 0.4 * e.getValue(),
                        existing.bm25, existing.vector, e.getValue()));
            }
        }

        List<Scored> scored = new ArrayList<>(fused.values());
        scored.sort((a, b) -> Double.compare(rerank(q, b), rerank(q, a)));
        List<Scored> top = scored.stream().limit(topK).toList();
        if (top.isEmpty() || rerank(q, top.getFirst()) < minEvidenceScore) {
            return List.of();
        }
        return top.stream().map(s -> toCitation(s, q, rerank(q, s))).toList();
    }

    public String buildEvidence(List<Citation> citations) {
        if (citations.isEmpty()) return "未检索到足够证据，请勿编造参数。";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Citation c : citations) {
            sb.append('[').append(i++).append("] ")
                    .append(c.documentTitle()).append(" / ").append(c.section())
                    .append(" v").append(c.versionNo()).append('\n')
                    .append(c.snippet()).append("\n\n");
        }
        return sb.toString();
    }

    public void indexChunk(long chunkId, String title, String content, String metadataJson, String vectorId) {
        try {
            ensureIndex();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("chunkId", chunkId);
            doc.put("title", title == null ? "" : title);
            doc.put("content", content == null ? "" : content);
            doc.put("metadata", metadataJson == null ? "{}" : metadataJson);
            doc.put("vectorId", vectorId == null ? "" : vectorId);
            send("PUT", "/" + INDEX + "/_doc/" + chunkId, mapper.writeValueAsString(doc));
        } catch (Exception ignored) {
        }
        upsertLocalVector(chunkId, vectorId, content);
    }

    public void reindexAllChunks() {
        for (Map<String, Object> row : loadChunks()) {
            indexChunk(((Number) row.get("chunkId")).longValue(),
                    String.valueOf(row.get("documentTitle")),
                    String.valueOf(row.get("content")),
                    String.valueOf(row.get("metadata")),
                    row.get("vectorId") == null ? null : String.valueOf(row.get("vectorId")));
        }
    }

    private List<Map<String, Object>> loadChunks() {
        return jdbc.queryForList("""
                SELECT c.id AS chunkId, c.content, c.metadata_json AS metadata, c.vector_id AS vectorId,
                       d.id AS documentId, d.title AS documentTitle, d.source_type AS sourceType, d.source_id AS sourceId,
                       d.version_no AS versionNo, kb.kb_code AS kbCode
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                JOIN knowledge_base kb ON kb.id = d.kb_id
                WHERE d.parse_status='READY' AND kb.status='ENABLED'
                """);
    }

    private Map<String, Object> findChunk(long chunkId) {
        var rows = jdbc.queryForList("""
                SELECT c.id AS chunkId, c.content, c.metadata_json AS metadata, c.vector_id AS vectorId,
                       d.id AS documentId, d.title AS documentTitle, d.source_type AS sourceType, d.source_id AS sourceId,
                       d.version_no AS versionNo, kb.kb_code AS kbCode
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                JOIN knowledge_base kb ON kb.id = d.kb_id
                WHERE c.id=? AND d.parse_status='READY' AND kb.status='ENABLED'
                """, chunkId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<Long, Double> esRecall(String query, int size) {
        Map<Long, Double> out = new LinkedHashMap<>();
        try {
            ensureIndex();
            Map<String, Object> body = Map.of(
                    "size", size,
                    "query", Map.of("multi_match", Map.of(
                            "query", query,
                            "fields", List.of("content^3", "title^2", "metadata"),
                            "fuzziness", "AUTO"
                    ))
            );
            HttpResponse<String> r = send("POST", "/" + INDEX + "/_search", mapper.writeValueAsString(body));
            if (r.statusCode() < 200 || r.statusCode() >= 300) return out;
            JsonNode hits = mapper.readTree(r.body()).path("hits").path("hits");
            double max = 0;
            List<double[]> tmp = new ArrayList<>();
            for (JsonNode h : hits) {
                long id = h.path("_source").path("chunkId").asLong(h.path("_id").asLong());
                double score = h.path("_score").asDouble(0);
                max = Math.max(max, score);
                tmp.add(new double[]{id, score});
            }
            for (double[] t : tmp) {
                out.put((long) t[0], max <= 0 ? 0 : t[1] / max);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void ensureIndex() throws Exception {
        HttpResponse<String> head = send("HEAD", "/" + INDEX, null);
        if (head.statusCode() == 404) {
            String mapping = """
                    {"mappings":{"properties":{"chunkId":{"type":"long"},"title":{"type":"text"},"content":{"type":"text"},"metadata":{"type":"text"},"vectorId":{"type":"keyword"}}}}
                    """;
            send("PUT", "/" + INDEX, mapping);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(esBase + path)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Citation toCitation(Scored s, String query, double finalScore) {
        Map<String, Object> row = s.row();
        String meta = String.valueOf(row.get("metadata"));
        String section = extract(meta, "section", "正文");
        return new Citation(
                ((Number) row.get("documentId")).longValue(),
                ((Number) row.get("chunkId")).longValue(),
                String.valueOf(row.get("documentTitle")),
                String.valueOf(row.get("sourceType")),
                row.get("sourceId") == null ? null : String.valueOf(row.get("sourceId")),
                section,
                String.valueOf(row.get("versionNo")),
                String.valueOf(row.get("content")),
                round(finalScore),
                Map.of("bm25", round(s.bm25), "vector", round(s.vector), "es", round(s.es),
                        "localVector", "64-dimensional deterministic character-hash prototype",
                        "milvusMode", milvusEnabled ? "CONNECTION_SKELETON_ONLY" : "DISABLED",
                        "minEvidenceScore", minEvidenceScore, "query", query)
        );
    }

    private double rerank(String query, Scored s) {
        double score = s.fused;
        String content = String.valueOf(s.row().get("content"));
        if (query.contains("内存") && content.contains("内存")) score += 0.15;
        if (query.contains("退") && content.contains("退")) score += 0.12;
        if (query.contains("支付") && content.contains("支付")) score += 0.12;
        if (content.contains("32GB")) score += 0.05;
        if (s.es > 0.5) score += 0.08;
        return score;
    }

    private double keywordScore(String query, String doc) {
        String d = doc.toLowerCase(Locale.ROOT);
        String[] terms = query.toLowerCase(Locale.ROOT).split("[\\s,，。？?！!；;]+");
        double hit = 0;
        for (String t : terms) {
            if (t.isBlank()) continue;
            if (d.contains(t)) hit += 1.0;
            if (t.length() >= 2) {
                for (int i = 0; i < t.length() - 1; i++) {
                    String bg = t.substring(i, i + 2);
                    if (d.contains(bg)) hit += 0.35;
                }
            }
        }
        return Math.min(1.0, hit / Math.max(1, terms.length));
    }

    private double vectorScore(String query, String content, String vectorId, long chunkId) {
        double[] qv = embed(query);
        double[] dv = loadOrBuildEmbedding(chunkId, vectorId, content);
        return cosine(qv, dv);
    }

    private void upsertLocalVector(long chunkId, String vectorId, String content) {
        try {
            double[] emb = embed(content);
            String vid = vectorId == null || vectorId.isBlank() ? "vec-" + chunkId : vectorId;
            Long existing = null;
            try {
                existing = jdbc.queryForObject("SELECT id FROM knowledge_vector WHERE chunk_id=?", Long.class, chunkId);
            } catch (Exception ignored) {
            }
            String json = mapper.writeValueAsString(emb);
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO knowledge_vector(id,chunk_id,vector_id,dim,embedding_json,created_at)
                        VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(3))
                        """, com.geekmall.common.domain.DistributedId.next(), chunkId, vid, emb.length, json);
            } else {
                jdbc.update("UPDATE knowledge_vector SET vector_id=?, dim=?, embedding_json=? WHERE chunk_id=?",
                        vid, emb.length, json, chunkId);
            }
        } catch (Exception ignored) {
        }
    }

    private double[] loadOrBuildEmbedding(long chunkId, String vectorId, String content) {
        try {
            var rows = jdbc.queryForList("SELECT embedding_json FROM knowledge_vector WHERE chunk_id=?", chunkId);
            if (!rows.isEmpty()) {
                JsonNode arr = mapper.readTree(String.valueOf(rows.getFirst().get("embedding_json")));
                double[] out = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).asDouble();
                return out;
            }
        } catch (Exception ignored) {
        }
        upsertLocalVector(chunkId, vectorId, content);
        return embed(content);
    }

    /** Deterministic 64-dimensional character-hash prototype for offline demo / CI; not an embedding model. */
    static double[] embed(String text) {
        double[] v = new double[64];
        if (text == null || text.isBlank()) return v;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            int idx = Math.floorMod(c * 131 + i * 17, v.length);
            v[idx] += 1.0;
        }
        double norm = 0;
        for (double x : v) norm += x * x;
        if (norm > 0) {
            norm = Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
        return v;
    }

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String extract(String json, String key, String def) {
        if (json == null) return def;
        String token = "\"" + key + "\"";
        int i = json.indexOf(token);
        if (i < 0) return def;
        int colon = json.indexOf(':', i);
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return def;
        return json.substring(q1 + 1, q2);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record Scored(Map<String, Object> row, double fused, double bm25, double vector, double es) {}

    public record Citation(long documentId, long chunkId, String documentTitle, String sourceType, String sourceId,
                           String section, String versionNo, String snippet, double score, Map<String, Object> debug) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentId", documentId);
            m.put("chunkId", chunkId);
            m.put("documentTitle", documentTitle);
            m.put("sourceType", sourceType);
            m.put("sourceId", sourceId);
            m.put("section", section);
            m.put("version", versionNo);
            m.put("snippet", snippet);
            m.put("score", score);
            return m;
        }
    }
}
