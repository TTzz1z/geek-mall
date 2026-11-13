package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeIngestService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeDocumentParser parser;
    private final TransactionTemplate tx;

    public KnowledgeIngestService(JdbcTemplate jdbc, ObjectMapper mapper, KnowledgeDocumentParser parser,
                                  PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.parser = parser;
        this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> ingest(IngestRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new BusinessException("AI40002", "title 必填");
        }
        String mime = req.mimeType() == null ? "text/plain" : req.mimeType().toLowerCase(Locale.ROOT);
        KnowledgeDocumentParser.Parsed parsed;
        if ((req.fileUrl() != null && !req.fileUrl().isBlank()) || !mime.startsWith("text/")) {
            parsed = parser.parse(req.fileUrl());
        } else {
            parsed = new KnowledgeDocumentParser.Parsed(req.content(), mime, false);
        }
        if (parsed.text() == null || parsed.text().isBlank() || parsed.text().length() > 240_000) {
            throw new BusinessException("AI40004", "内容为空或超出入库限制");
        }
        return tx.execute(s -> store(req, parsed));
    }

    private Map<String, Object> store(IngestRequest req, KnowledgeDocumentParser.Parsed parsed) {
        long kbId = resolveKb(req.kbCode());
        long docId = DistributedId.next();
        String sourceType = req.sourceType() == null ? "UPLOAD" : req.sourceType();
        String sourceId = req.sourceId();
        if (sourceId == null || sourceId.isBlank()) {
            sourceId = req.fileUrl() == null ? "document-" + docId
                    : UUID.nameUUIDFromBytes(req.fileUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
        var previous = jdbc.queryForList("""
                SELECT version_no FROM knowledge_document
                WHERE kb_id=? AND source_type=? AND source_id=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE
                """, Integer.class, kbId, sourceType, sourceId);
        int version = previous.isEmpty() ? 1 : previous.getFirst() + 1;
        jdbc.update("""
                UPDATE knowledge_document SET parse_status='DISABLED'
                WHERE kb_id=? AND source_type=? AND source_id=? AND parse_status='READY'
                """, kbId, sourceType, sourceId);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO knowledge_document(id,kb_id,source_type,source_id,title,file_url,parse_status,version_no,created_at)
                VALUES(?,?,?,?,?,?,'READY',?,?)
                """, docId, kbId, sourceType, sourceId, req.title(), req.fileUrl(), version, Timestamp.from(now));

        List<Map<String, Object>> chunks = new ArrayList<>();
        int no = 1;
        for (String part : chunkText(parsed.text())) {
            long chunkId = DistributedId.next();
            String meta = json(Map.of(
                    "section", parsed.ocr() ? "OCR" : "正文",
                    "ocr", parsed.ocr(),
                    "mime", parsed.mime()
            ));
            String vectorId = "vec-ingest-" + chunkId;
            jdbc.update("""
                    INSERT INTO knowledge_chunk(id,document_id,chunk_no,content,metadata_json,vector_id)
                    VALUES(?,?,?,?,?,?)
                    """, chunkId, docId, no++, part, meta, vectorId);
            chunks.add(Map.of("chunkId", chunkId, "chunkNo", no - 1, "vectorId", vectorId,
                    "preview", part.length() > 80 ? part.substring(0, 80) + "…" : part));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", docId);
        out.put("title", req.title());
        out.put("parseStatus", "READY");
        out.put("indexStatus", "PENDING");
        out.put("kbCode", req.kbCode() == null || req.kbCode().isBlank() ? "PRODUCT" : req.kbCode());
        out.put("ocrUsed", parsed.ocr());
        out.put("vlUsed", false);
        out.put("version", version);
        out.put("sourceId", sourceId);
        out.put("chunkCount", chunks.size());
        out.put("chunks", chunks);
        out.put("rawTextPreview", parsed.text().length() > 200 ? parsed.text().substring(0, 200) + "…" : parsed.text());
        return out;
    }

    @Transactional
    public Map<String, Object> syncProduct(Map<String, Object> product, String action) {
        if (product == null || product.get("id") == null) {
            throw new BusinessException("AI40005", "product.id 必填");
        }
        String sourceId = String.valueOf(product.get("id"));
        String act = action == null ? "UPSERT" : action.toUpperCase(Locale.ROOT);
        if ("DEACTIVATE".equals(act) || "OFF_SALE".equals(act)) {
            int n = jdbc.update("""
                    UPDATE knowledge_document SET parse_status='DISABLED'
                    WHERE source_type='PRODUCT_SPU' AND source_id=?
                    """, sourceId);
            return Map.of("action", "DEACTIVATE", "sourceId", sourceId, "updated", n);
        }
        String title = String.valueOf(product.getOrDefault("title", "商品 " + sourceId));
        String content = buildProductText(product);
        return ingest(new IngestRequest("PRODUCT", "PRODUCT_SPU", sourceId, title, "text/plain", null, content));
    }

    public int countActiveBySource(String sourceId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(1) FROM knowledge_document
                WHERE source_type='PRODUCT_SPU' AND source_id=? AND parse_status='READY'
                """, Integer.class, sourceId);
        return n == null ? 0 : n;
    }

    private String buildProductText(Map<String, Object> product) {
        StringBuilder sb = new StringBuilder();
        sb.append("商品：").append(product.getOrDefault("title", "")).append('\n');
        sb.append("副标题：").append(product.getOrDefault("subtitle", "")).append('\n');
        sb.append("品牌：").append(product.getOrDefault("brandName", "")).append('\n');
        sb.append("类目：").append(product.getOrDefault("categoryName", "")).append('\n');
        sb.append("店铺：").append(product.getOrDefault("shopName", "")).append('\n');
        sb.append("销售主体：").append(product.getOrDefault("sellerLabel", "")).append('\n');
        Object attrs = product.get("attributes");
        if (attrs instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    sb.append(m.get("name")).append('：').append(m.get("value")).append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    private List<String> chunkText(String text) {
        List<String> parts = new ArrayList<>();
        String[] paras = text.split("[\\n\\r]+");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String t = p.trim();
            if (t.isBlank()) continue;
            if (t.length() > 280) {
                if (!buf.isEmpty()) {
                    parts.add(buf.toString());
                    buf.setLength(0);
                }
                int[] codePoints = t.codePoints().toArray();
                for (int start = 0; start < codePoints.length; start += 280) {
                    parts.add(new String(codePoints, start, Math.min(280, codePoints.length - start)));
                }
                continue;
            }
            if (buf.length() + t.length() > 280 && buf.length() > 0) {
                parts.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append('\n');
            buf.append(t);
        }
        if (!buf.isEmpty()) parts.add(buf.toString().trim());
        if (parts.isEmpty()) parts.add(text);
        return parts;
    }

    private long resolveKb(String kbCode) {
        String code = kbCode == null || kbCode.isBlank() ? "PRODUCT" : kbCode;
        var rows = jdbc.queryForList("SELECT id FROM knowledge_base WHERE kb_code=? AND status='ENABLED' FOR UPDATE", code);
        if (rows.isEmpty()) throw new BusinessException("AI40405", "知识库不存在: " + code);
        return ((Number) rows.getFirst().get("id")).longValue();
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record IngestRequest(String kbCode, String sourceType, String sourceId, String title,
                                String mimeType, String fileUrl, String content) {}

}
