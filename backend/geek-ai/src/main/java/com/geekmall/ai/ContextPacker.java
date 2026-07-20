package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Packs recent dialog + slots for the planner user prompt (Phase B). */
@Component
public class ContextPacker {
    private static final int HISTORY_LIMIT = 4;
    private static final int LINE_MAX = 160;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ContextPacker(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record PackedContext(
            String userPrompt,
            Map<String, Object> slots,
            List<Map<String, String>> recentDialog
    ) {}

    /**
     * @param conversationId conversation PK
     * @param excludeMessageId optional just-inserted user message to skip from history
     */
    public PackedContext pack(long conversationId, Long excludeMessageId,
                              String message, String sceneHint, Map<String, Object> slots) {
        List<Map<String, String>> recent = loadRecent(conversationId, excludeMessageId);
        String hint = sceneHint == null || sceneHint.isBlank() ? "auto" : sceneHint;
        StringBuilder sb = new StringBuilder();
        sb.append("sceneHint=").append(hint).append('\n');
        sb.append("currentSlots=").append(toJson(slots == null ? Map.of() : slots)).append('\n');
        sb.append("recentDialog:\n");
        if (recent.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Map<String, String> row : recent) {
                sb.append(row.get("role")).append(": ").append(row.get("content")).append('\n');
            }
        }
        sb.append("userMessage=").append(message == null ? "" : message).append('\n');
        sb.append("availableTools=").append(ToolPolicy.schemasForPrompt());
        return new PackedContext(sb.toString(), slots == null ? Map.of() : slots, recent);
    }

    private List<Map<String, String>> loadRecent(long conversationId, Long excludeMessageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, role, content FROM ai_message
                WHERE conversation_id=?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, conversationId, HISTORY_LIMIT + 2);
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (excludeMessageId != null && excludeMessageId.equals(((Number) row.get("id")).longValue())) {
                continue;
            }
            String role = String.valueOf(row.get("role"));
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) continue;
            Map<String, String> line = new LinkedHashMap<>();
            line.put("role", role);
            line.put("content", truncate(String.valueOf(row.get("content")), LINE_MAX));
            out.add(line);
            if (out.size() >= HISTORY_LIMIT) break;
        }
        // chronological for the prompt
        java.util.Collections.reverse(out);
        return out;
    }

    private String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
