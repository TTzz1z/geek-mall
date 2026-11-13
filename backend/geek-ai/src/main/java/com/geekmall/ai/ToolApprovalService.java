package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Service
class ToolApprovalService {
    private static final Logger log = LoggerFactory.getLogger(ToolApprovalService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ToolExecutor tools;
    private final TransactionTemplate tx;

    ToolApprovalService(JdbcTemplate jdbc, ObjectMapper mapper, ToolExecutor tools, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tools = tools;
        this.tx = new TransactionTemplate(manager);
    }

    String request(long toolId, long userId) {
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ai_confirmation(id,tool_call_id,confirmation_token,user_id,expires_at,status,created_at,execution_enabled)
                VALUES(?,?,?,?,TIMESTAMPADD(SECOND,600,CURRENT_TIMESTAMP),'PENDING',CURRENT_TIMESTAMP,TRUE)
                """, DistributedId.next(), toolId, token, userId);
        return token;
    }

    Map<String, Object> confirm(String token, long userId) {
        return transition(token, userId, true);
    }

    Map<String, Object> cancel(String token, long userId) {
        return transition(token, userId, false);
    }

    Map<String, Object> status(String token, long userId) {
        var rows = jdbc.queryForList("""
                SELECT status,draft_no FROM ai_confirmation WHERE confirmation_token=? AND user_id=?
                """, token, userId);
        if (rows.isEmpty()) throw new BusinessException("AI40402", "确认令牌不存在");
        var row = rows.getFirst();
        return response(token, String.valueOf(row.get("status")), (String) row.get("draft_no"));
    }

    private Map<String, Object> transition(String token, long userId, boolean approve) {
        return tx.execute(s -> {
            var rows = jdbc.queryForList("""
                    SELECT id,tool_call_id,user_id,status,draft_no,execution_enabled,
                      expires_at<=CURRENT_TIMESTAMP AS expired
                    FROM ai_confirmation WHERE confirmation_token=? FOR UPDATE
                    """, token);
            if (rows.isEmpty()) throw new BusinessException("AI40402", "确认令牌不存在");
            var row = rows.getFirst();
            if (((Number) row.get("user_id")).longValue() != userId) {
                throw new BusinessException("AI40303", "确认令牌不属于当前用户");
            }
            String current = String.valueOf(row.get("status"));
            if ("PENDING".equals(current)) {
                boolean expired = truthy(row.get("expired")) || !truthy(row.get("execution_enabled"));
                current = expired ? "EXPIRED" : approve ? "CONFIRMED" : "CANCELLED";
                setStatus(row, current, null);
            }
            return response(token, current, (String) row.get("draft_no"));
        });
    }

    @Scheduled(fixedDelayString = "${geek.tools.approval-interval:1000}")
    void advance() {
        for (Long id : jdbc.queryForList("""
                SELECT id FROM ai_confirmation
                WHERE (status='CONFIRMED' AND execution_enabled=TRUE)
                   OR (status='PENDING' AND (expires_at<=CURRENT_TIMESTAMP OR execution_enabled=FALSE))
                ORDER BY id LIMIT 50
                """, Long.class)) {
            try {
                tx.executeWithoutResult(s -> advanceLocked(id));
            } catch (RuntimeException e) {
                log.warn("Tool approval {} will retry ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    private void advanceLocked(long id) {
        var approval = jdbc.queryForMap("""
                SELECT c.id,c.tool_call_id,c.user_id,c.status,c.execution_enabled,
                  c.expires_at<=CURRENT_TIMESTAMP AS expired,t.tool_name,t.arguments_json
                FROM ai_confirmation c JOIN ai_tool_call t ON t.id=c.tool_call_id
                WHERE c.id=? FOR UPDATE
                """, id);
        String status = String.valueOf(approval.get("status"));
        if ("PENDING".equals(status) && (truthy(approval.get("expired")) || !truthy(approval.get("execution_enabled")))) {
            setStatus(approval, "EXPIRED", null);
        } else if ("CONFIRMED".equals(status) && truthy(approval.get("execution_enabled"))) {
            try {
                var args = mapper.readValue(String.valueOf(approval.get("arguments_json")), new TypeReference<Map<String, Object>>() {});
                String draft = tools.executeApprovedDraft(String.valueOf(approval.get("tool_name")), args,
                        ((Number) approval.get("user_id")).longValue(), id);
                setStatus(approval, "EXECUTED", draft);
            } catch (BusinessException | com.fasterxml.jackson.core.JsonProcessingException e) {
                setStatus(approval, "DENIED", null);
            }
        }
    }

    private void setStatus(Map<String, Object> row, String status, String draftNo) {
        jdbc.update("UPDATE ai_confirmation SET status=?,draft_no=? WHERE id=?", status, draftNo, row.get("id"));
        jdbc.update("UPDATE ai_tool_call SET status=?,result_summary=? WHERE id=?", status, draftNo, row.get("tool_call_id"));
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    private static Map<String, Object> response(String token, String status, String draft) {
        String message = switch (status) {
            case "CONFIRMED" -> "已确认，等待生成草稿。";
            case "EXECUTED" -> "草稿已生成，编号 " + draft;
            case "EXPIRED" -> "确认已过期。";
            case "CANCELLED" -> "确认已取消。";
            case "DENIED" -> "工具执行已拒绝。";
            default -> "等待用户确认。";
        };
        return draft == null ? Map.of("confirmationToken", token, "status", status, "message", message)
                : Map.of("confirmationToken", token, "status", status, "message", message, "draftNo", draft);
    }
}
