package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KnowledgeIndexRecoveryTest {
    JdbcTemplate jdbc;
    HybridRagService rag;
    KnowledgeIngestService ingest;
    HttpServer server;
    volatile boolean exists;
    volatile boolean failBulk;
    volatile int bulkCalls;

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE knowledge_base(id BIGINT PRIMARY KEY,kb_code VARCHAR(64),scope_type VARCHAR(32),status VARCHAR(32));
                CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,kb_id BIGINT,source_type VARCHAR(64),source_id VARCHAR(64),
                  title VARCHAR(255),file_url VARCHAR(500),parse_status VARCHAR(32),version_no INT,created_at TIMESTAMP,
                  activation_status VARCHAR(32) DEFAULT 'CANDIDATE',index_cleanup_pending BOOLEAN DEFAULT FALSE);
                CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,chunk_no INT,content CLOB,
                  metadata_json VARCHAR(500),vector_id VARCHAR(64),index_profile VARCHAR(190),index_retry_at TIMESTAMP);
                CREATE TABLE knowledge_index_state(index_name VARCHAR(128) PRIMARY KEY,generation BIGINT NOT NULL,
                  model_profile VARCHAR(190),status VARCHAR(32),last_error VARCHAR(255),updated_at TIMESTAMP);
                INSERT INTO knowledge_base VALUES(1,'PRODUCT','PUBLIC','ENABLED');
                INSERT INTO knowledge_document(id,kb_id,source_type,source_id,title,parse_status,version_no,activation_status)
                  VALUES(10,1,'PRODUCT_SPU','1001','old active','READY',1,'ACTIVE');
                INSERT INTO knowledge_chunk(id,document_id,chunk_no,content,metadata_json) VALUES(11,10,1,'old evidence','{}');
                """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int code = 200;
            String response = "{}";
            if (exchange.getRequestMethod().equals("HEAD")) code = exists ? 200 : 404;
            else if (exchange.getRequestMethod().equals("PUT")) exists = true;
            else if (path.endsWith("/_bulk")) {
                bulkCalls++;
                response = failBulk ? "{\"errors\":true}" : "{\"errors\":false}";
            } else if (path.endsWith("/_search")) response = "{\"hits\":{\"hits\":[{\"_id\":\"11\"}]}}";
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
            try (var output = exchange.getResponseBody()) {
                if (!exchange.getRequestMethod().equals("HEAD")) output.write(body);
            }
        });
        server.start();
        rag = new HybridRagService(jdbc, new ObjectMapper(), new EmbeddingClient("", "unused", ""),
                "http://127.0.0.1:" + server.getAddress().getPort(), "kb");
        ingest = new KnowledgeIngestService(jdbc, new ObjectMapper(), mock(KnowledgeDocumentParser.class),
                new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void failedCandidateKeepsOldActiveAndActivatesOnlyAfterSuccessfulRetry() {
        rag.indexPending();
        var candidate = ingest.syncProduct(Map.of("id", 1001, "title", "new version"), "UPSERT");
        assertEquals("CANDIDATE", candidate.get("activationStatus"));
        failBulk = true;
        rag.indexPending();
        assertEquals("ACTIVE", status(10));
        assertEquals("CANDIDATE", status((Long) candidate.get("documentId")));
        assertEquals(1, rag.retrieve("old", 3).size());
        failBulk = false;
        jdbc.update("UPDATE knowledge_chunk SET index_retry_at=NULL");
        rag.indexPending();
        assertEquals("SUPERSEDED", status(10));
        assertEquals("ACTIVE", status((Long) candidate.get("documentId")));
        assertEquals(1, ingest.countActiveBySource("1001"));
    }

    @Test
    void detectsDeletedIndexEvenWhenSqlReportsAllChunksIndexed() {
        rag.indexPending();
        assertTrue(rag.indexingComplete());
        int first = bulkCalls;
        exists = false;
        rag.indexPending();
        assertTrue(exists);
        assertTrue(bulkCalls > first);
        assertEquals(2L, ((Number) rag.indexStatus().get("generation")).longValue());
        assertTrue(rag.indexingComplete());
    }

    @Test
    void explicitDeactivationFencesPendingCandidateAndOldElasticsearchHitsImmediately() {
        rag.indexPending();
        var candidate = ingest.syncProduct(Map.of("id", 1001, "title", "new version"), "UPSERT");
        ingest.syncProduct(Map.of("id", 1001), "OFF_SALE");
        assertTrue(rag.retrieve("old", 3).isEmpty());
        rag.indexPending();
        assertEquals("INACTIVE", status(10));
        assertEquals("INACTIVE", status((Long) candidate.get("documentId")));
        assertEquals(0, ingest.countActiveBySource("1001"));
    }

    @Test
    void administrativeRebuildIsDurableAndRetryable() {
        rag.indexPending();
        var rebuild = rag.rebuildIndex();
        assertEquals("BUILDING", rebuild.get("status"));
        assertFalse(rag.indexingComplete());
        failBulk = true;
        rag.indexPending();
        assertEquals("RETRYING", rag.indexStatus().get("status"));
        assertEquals("ACTIVE", status(10));
        failBulk = false;
        jdbc.update("UPDATE knowledge_chunk SET index_retry_at=NULL");
        rag.indexPending();
        assertTrue(rag.indexingComplete());
        assertEquals("READY", rag.indexStatus().get("status"));
    }

    @Test
    void versionRollbackRebuildsRetiredChunksBeforeReplacingCurrentVersion() {
        rag.indexPending();
        var newer = ingest.syncProduct(Map.of("id", 1001, "title", "new version"), "UPSERT");
        rag.indexPending();
        assertEquals("SUPERSEDED", status(10));
        rag.requestActivation(10);
        assertEquals("ACTIVE", status((Long) newer.get("documentId")));
        assertEquals("CANDIDATE", status(10));
        rag.indexPending();
        assertEquals("ACTIVE", status(10));
        assertEquals("SUPERSEDED", status((Long) newer.get("documentId")));
    }

    @Test
    void candidateWithMoreThanOneBatchNeverActivatesPartially() {
        rag.indexPending();
        long candidate = (Long) ingest.syncProduct(Map.of("id", 1001, "title", "new version"), "UPSERT").get("documentId");
        for (int i = 0; i < 17; i++) jdbc.update("""
                INSERT INTO knowledge_chunk(id,document_id,chunk_no,content,metadata_json)
                VALUES(?,?,?,'additional evidence','{}')
                """, 100 + i, candidate, i + 2);
        rag.indexPending();
        assertEquals("ACTIVE", status(10));
        assertEquals("CANDIDATE", status(candidate));
        rag.indexPending();
        assertEquals("SUPERSEDED", status(10));
        assertEquals("ACTIVE", status(candidate));
    }

    private String status(long document) {
        return jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=?", String.class, document);
    }
}
