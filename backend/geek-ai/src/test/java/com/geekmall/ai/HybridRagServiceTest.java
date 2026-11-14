package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HybridRagServiceTest {
    @Test
    void semanticAdmissionRejectsLexicalHitsAndFailsClosedOnEmbeddingFailure() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:semantic_gate;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var mapper = new ObjectMapper();
        var unavailable = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (unavailable.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            var vector = new java.util.ArrayList<>(java.util.Collections.nCopies(1024, 0.0));
            vector.set(0, 1.0);
            byte[] body = mapper.writeValueAsBytes(Map.of("data", List.of(Map.of("index", 0, "embedding", vector))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/kb/_search", exchange -> {
            var query = mapper.readTree(exchange.getRequestBody());
            if (query.has("knn")) assertEquals(0.62, query.path("knn").path("similarity").asDouble());
            byte[] body = mapper.writeValueAsBytes(Map.of("hits", Map.of("hits",
                    query.has("knn") ? List.of() : List.of(Map.of("_id", "1")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var jdbc = new JdbcTemplate(ds);
            var rag = new HybridRagService(jdbc, mapper, new EmbeddingClient(endpoint, "bge-m3", ""), endpoint, "kb", 0.62);
            assertTrue(rag.retrieve("unsupported question", 5).isEmpty());
            unavailable.set(true);
            assertTrue(rag.retrieve("unsupported question", 5).isEmpty());
            assertThrows(com.geekmall.common.api.BusinessException.class,
                    () -> rag.retrieveForEvaluation("unsupported question", 5, "PRODUCT"));
            var missing = new HybridRagService(jdbc, mapper, new EmbeddingClient("", "bge-m3", ""), endpoint, "kb", 0.62);
            assertTrue(missing.retrieve("question", 5).isEmpty());
            assertThrows(com.geekmall.common.api.BusinessException.class,
                    () -> missing.retrieveForEvaluation("question", 5, "PRODUCT"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fusesIndependentRankings() {
        var ranking = HybridRagService.reciprocalRanks(List.of(4L, 2L, 5L), List.of(8L, 2L, 5L));
        assertEquals(List.of(2L, 5L, 4L, 8L), List.copyOf(ranking.keySet()));
    }

    @Test
    void evaluationPenalizesMissingAndPoorlyRankedSources() {
        var scores = RetrievalEvaluation.score(List.of("irrelevant", "B", "B"), Map.of("A", 3, "B", 1), 3);
        assertEquals(0.5, scores.recall());
        assertEquals(0.5, scores.mrr());
        assertTrue(scores.ndcg() > 0 && scores.ndcg() < 0.2);
    }

    @Test
    void filtersCandidatesInElasticsearchAndRechecksDatabaseScopeAndStatus() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rag_scope;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE knowledge_base(id BIGINT PRIMARY KEY,kb_code VARCHAR(64),scope_type VARCHAR(32),status VARCHAR(32));
                CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,kb_id BIGINT,source_type VARCHAR(64),source_id VARCHAR(64),
                    title VARCHAR(255),parse_status VARCHAR(32),version_no INT,activation_status VARCHAR(32) DEFAULT 'ACTIVE');
                CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,content CLOB,metadata_json VARCHAR(500));
                INSERT INTO knowledge_base VALUES(1,'PRODUCT','PUBLIC','ENABLED'),(2,'RULE','PUBLIC','ENABLED'),
                    (3,'PRODUCT','PRIVATE','ENABLED');
                INSERT INTO knowledge_document(id,kb_id,source_type,source_id,title,parse_status,version_no) VALUES(1,1,'MANUAL','P1','Public product','READY',2),
                    (2,2,'RULE','R1','Public rule','READY',1),(3,3,'MANUAL','P2','Private product','READY',1),
                    (4,1,'MANUAL','P1','Retired version','DISABLED',1);
                INSERT INTO knowledge_chunk VALUES(1,1,'product text','{}'),(2,2,'rule text','{}'),
                    (3,3,'private text','{}'),(4,4,'old text','{}');
                """);
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kb/_search", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"hits\":{\"hits\":[{\"_id\":\"2\"},{\"_id\":\"3\"},{\"_id\":\"4\"},{\"_id\":\"1\"}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var rag = new HybridRagService(jdbc, new ObjectMapper(), new EmbeddingClient("", "unused", ""),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "kb");
            assertEquals(List.of("product text"), rag.retrieve("question", 3, "PRODUCT").stream()
                    .map(HybridRagService.Citation::snippet).toList());
            assertTrue(request.get().contains("\"kbCode\":\"PRODUCT\""));
            assertTrue(request.get().contains("\"scopeType\":\"PUBLIC\""));
            assertEquals(rag.retrieve("question", 3, "PRODUCT"), rag.retrieveForEvaluation("question", 3, "PRODUCT"));
            assertEquals(List.of("rule text"), rag.retrieve("question", 3, "RULE").stream()
                    .map(HybridRagService.Citation::snippet).toList());
            assertTrue(rag.retrieve("question", 3, "PRIVATE").isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
