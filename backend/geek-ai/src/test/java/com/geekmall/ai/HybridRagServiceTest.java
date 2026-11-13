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

import static org.junit.jupiter.api.Assertions.*;

class HybridRagServiceTest {
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
                    title VARCHAR(255),parse_status VARCHAR(32),version_no INT);
                CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,content CLOB,metadata_json VARCHAR(500));
                INSERT INTO knowledge_base VALUES(1,'PRODUCT','PUBLIC','ENABLED'),(2,'RULE','PUBLIC','ENABLED'),
                    (3,'PRODUCT','PRIVATE','ENABLED');
                INSERT INTO knowledge_document VALUES(1,1,'MANUAL','P1','Public product','READY',2),
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
            assertEquals(List.of("rule text"), rag.retrieve("question", 3, "RULE").stream()
                    .map(HybridRagService.Citation::snippet).toList());
            assertTrue(rag.retrieve("question", 3, "PRIVATE").isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
