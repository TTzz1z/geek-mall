package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class HybridRagServiceTest {
    @Test
    void hybridRetrieveAndRerankFindsMemorySpec() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ai_rag;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE knowledge_base(id BIGINT PRIMARY KEY,kb_code VARCHAR(64),name VARCHAR(128),scope_type VARCHAR(32),scope_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,kb_id BIGINT,source_type VARCHAR(64),source_id VARCHAR(64),title VARCHAR(255),file_url VARCHAR(500),parse_status VARCHAR(32),version_no INT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,chunk_no INT,content CLOB,metadata_json VARCHAR(500),vector_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE knowledge_vector(id BIGINT PRIMARY KEY,chunk_id BIGINT,vector_id VARCHAR(64),dim INT,embedding_json CLOB,created_at TIMESTAMP)");
        jdbc.update("INSERT INTO knowledge_base VALUES(1,'PRODUCT','p','PUBLIC',NULL,'ENABLED')");
        jdbc.update("INSERT INTO knowledge_document VALUES(1,1,'PRODUCT_SPEC','1001','Air14参数',NULL,'READY',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO knowledge_chunk VALUES(1,1,1,'CPU Ultra7；内存: 32GB LPDDR5x；存储 1TB','{\"section\":\"参数\"}','vec-1')");
        jdbc.update("INSERT INTO knowledge_chunk VALUES(2,1,2,'续航约12小时','{\"section\":\"续航\"}','vec-2')");

        HybridRagService rag = new HybridRagService(jdbc, new ObjectMapper(), "http://127.0.0.1:9", false, "localhost", 19530);
        var hits = rag.retrieve("极客本 Air 14 内存是多少", 2);
        assertFalse(hits.isEmpty());
        assertTrue(hits.getFirst().snippet().contains("32GB"));
        assertTrue(rag.buildEvidence(hits).contains("Air14参数"));
    }
}
