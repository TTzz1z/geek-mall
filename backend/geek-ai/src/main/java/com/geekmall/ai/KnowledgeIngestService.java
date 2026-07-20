package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeIngestService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HybridRagService rag;

    public KnowledgeIngestService(JdbcTemplate jdbc, ObjectMapper mapper, HybridRagService rag) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rag = rag;
    }

    @Transactional
    public Map<String, Object> ingest(IngestRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new BusinessException("AI40002", "title 必填");
        }
        String mime = req.mimeType() == null ? "text/plain" : req.mimeType().toLowerCase(Locale.ROOT);
        if (!(mime.startsWith("text/") || mime.contains("pdf") || mime.contains("word")
                || mime.contains("sheet") || mime.startsWith("image/"))) {
            throw new BusinessException("AI40003", "不支持的 MIME: " + mime);
        }

        long kbId = resolveKb(req.kbCode());
        ParseResult parsed = parse(req, mime);
        long docId = DistributedId.next();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO knowledge_document(id,kb_id,source_type,source_id,title,file_url,parse_status,version_no,created_at)
                VALUES(?,?,?,?,?,?,'READY',1,?)
                """, docId, kbId, req.sourceType() == null ? "UPLOAD" : req.sourceType(),
                req.sourceId(), req.title(), req.fileUrl(), Timestamp.from(now));

        List<Map<String, Object>> chunks = new ArrayList<>();
        int no = 1;
        for (String part : parsed.chunks()) {
            long chunkId = DistributedId.next();
            String meta = json(Map.of(
                    "section", parsed.section(),
                    "ocr", parsed.ocrUsed(),
                    "vl", parsed.vlUsed(),
                    "mime", mime
            ));
            String vectorId = "vec-ingest-" + chunkId;
            jdbc.update("""
                    INSERT INTO knowledge_chunk(id,document_id,chunk_no,content,metadata_json,vector_id)
                    VALUES(?,?,?,?,?,?)
                    """, chunkId, docId, no++, part, meta, vectorId);
            rag.indexChunk(chunkId, req.title(), part, meta, vectorId);
            chunks.add(Map.of("chunkId", chunkId, "chunkNo", no - 1, "vectorId", vectorId,
                    "preview", part.length() > 80 ? part.substring(0, 80) + "…" : part));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", docId);
        out.put("title", req.title());
        out.put("parseStatus", "READY");
        out.put("ocrUsed", parsed.ocrUsed());
        out.put("vlUsed", parsed.vlUsed());
        out.put("chunkCount", chunks.size());
        out.put("chunks", chunks);
        out.put("rawTextPreview", parsed.rawText().length() > 200 ? parsed.rawText().substring(0, 200) + "…" : parsed.rawText());
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
        // replace previous active docs for same SPU
        jdbc.update("""
                UPDATE knowledge_document SET parse_status='DISABLED'
                WHERE source_type='PRODUCT_SPU' AND source_id=? AND parse_status='READY'
                """, sourceId);
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
        sb.append("价格区间：").append(product.getOrDefault("minPrice", "")).append(" - ").append(product.getOrDefault("maxPrice", "")).append('\n');
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

    private ParseResult parse(IngestRequest req, String mime) {
        String raw = req.content() == null ? "" : req.content().trim();
        boolean ocr = false;
        boolean vl = false;
        String section = "正文";

        if (mime.startsWith("image/")) {
            ocr = true;
            vl = true;
            // OCR/VL 骨架：无真实云端模型时，基于标题/说明生成可检索描述，并保留原图引用。
            String title = req.title();
            String hint = raw.isBlank() ? "" : raw;
            raw = "【OCR】" + title + " 图片文字识别摘要：" + (hint.isBlank() ? "见原图参数与标注" : hint)
                    + "。【VL】画面描述：商品说明书/参数图，需结合结构化字段核对。"
                    + (req.fileUrl() == null ? "" : " 原图=" + req.fileUrl());
            section = "OCR-VL";
        } else if (mime.contains("pdf") || mime.contains("word") || mime.contains("sheet")) {
            if (raw.isBlank()) {
                raw = "【解析】" + req.title() + " 文档已接收，正文待完整抽取。";
            } else {
                raw = "【解析】" + raw;
            }
            section = "文档";
        }

        if (raw.isBlank()) {
            throw new BusinessException("AI40004", "内容为空，无法入库");
        }
        return new ParseResult(raw, chunkText(raw), section, ocr, vl);
    }

    private List<String> chunkText(String text) {
        List<String> parts = new ArrayList<>();
        String[] paras = text.split("[\\n\\r]+");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String t = p.trim();
            if (t.isBlank()) continue;
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
        var rows = jdbc.queryForList("SELECT id FROM knowledge_base WHERE kb_code=? AND status='ENABLED'", code);
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

    private record ParseResult(String rawText, List<String> chunks, String section, boolean ocrUsed, boolean vlUsed) {}
}
