package com.geekmall.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KnowledgeMigrationsIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void upgradesLegacyKnowledgeAndPreservesInactiveSeedDocuments() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var config = Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(dataSource);
        // Set up the V5 schema and supply the previous seed rows below.
        config.target("5").repeatableSqlMigrationPrefix("fixture").load().migrate();

        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO knowledge_base(id,kb_code,name,scope_type,status)
                VALUES (96101,'PRODUCT','商品知识库','PUBLIC','ENABLED'),
                       (96102,'RULE','平台规则知识库','PUBLIC','ENABLED')
                """);
        jdbc.update("""
                INSERT INTO knowledge_document(id,kb_id,source_type,source_id,title,file_url,parse_status,version_no,created_at)
                VALUES (96201,96101,'PRODUCT_MANUAL','SPU-1001','极客本 Air 14 说明书','/docs/air14-manual.pdf','READY',1,NOW()),
                       (96202,96101,'PRODUCT_SPEC','SPU-1001','极客本 Air 14 参数表',NULL,'DISABLED',1,NOW()),
                       (96204,96102,'PLATFORM_RULE','PAYMENT','支付与发票规则',NULL,'REPARSE_REQUIRED',1,NOW()),
                       (98101,96101,'UPLOAD',NULL,'Legacy file','/docs/legacy.pdf','READY',1,NOW()),
                       (98102,96101,'UPLOAD','current-pdf','Parsed PDF','/docs/current.pdf','READY',2,NOW()),
                       (98103,96101,'UPLOAD','  ','Legacy text','  ','READY',1,NOW()),
                       (98104,96101,'UPLOAD','current-pdf','Newer parsed PDF',NULL,'READY',3,NOW())
                """);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,document_id,chunk_no,content,metadata_json)
                VALUES (96301,96201,1,'极客本 Air 14 采用 14 英寸 2.8K 屏，整机约 1.2kg，适合办公与轻度创作。',
                        '{"productId":1001,"section":"概述","version":"v1"}'),
                       (98111,98101,1,'Caller-supplied text','{"section":"正文","ocr":false,"vl":false,"mime":"text/plain"}'),
                       (98112,98102,1,'Extracted PDF text','{"section":"正文","ocr":false,"mime":"application/pdf"}'),
                       (98113,98103,1,'Actual plain text','{"section":"正文","ocr":false,"vl":false,"mime":"text/plain"}')
                """);

        var head = config.target("latest").repeatableSqlMigrationPrefix("R").load();
        assertThat(head.migrate().migrationsExecuted).isPositive();
        head.validate();

        assertThat(jdbc.query("""
                SELECT id,source_id,file_url,parse_status,version_no FROM knowledge_document
                WHERE id IN (96201,96202,96204,98101,98102,98103) ORDER BY id
                """, (rs, row) -> new Document(rs.getLong("id"), rs.getString("source_id"),
                rs.getString("file_url"), rs.getString("parse_status"), rs.getInt("version_no"))))
                .containsExactly(
                        new Document(96201, "SPU-1001", null, "READY", 1),
                        new Document(96202, "SPU-1001", null, "DISABLED", 1),
                        new Document(96204, "PAYMENT", null, "REPARSE_REQUIRED", 1),
                        new Document(98101, "legacy-document-98101", "/docs/legacy.pdf", "REPARSE_REQUIRED", 1),
                        new Document(98102, "current-pdf", "/docs/current.pdf", "READY", 2),
                        new Document(98103, "legacy-document-98103", "  ", "READY", 1));
        assertThat(jdbc.queryForList("""
                SELECT id FROM knowledge_chunk WHERE document_id=96201
                  AND metadata_json->>'$.mime'='text/plain' AND metadata_json->>'$.provenance'='curated' ORDER BY id
                """, Long.class)).containsExactly(96301L, 96302L);
        assertThat(jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=98102", String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=98104", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT id FROM knowledge_document WHERE activation_status='ACTIVE' AND parse_status<>'READY'", Long.class))
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=96202", String.class))
                .isEqualTo("INACTIVE");
    }

    private record Document(long id, String sourceId, String fileUrl, String status, int version) {}
}
