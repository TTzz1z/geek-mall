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
        return tx.execute(s -> {
            var rows = jdbc.queryForList("""
                    SELECT t.tool_name,t.arguments_json,t.status,v.user_id
                    FROM ai_tool_call t JOIN ai_conversation v ON v.id=t.conversation_id
                    WHERE t.id=? FOR UPDATE
                    """, toolId);
            if (rows.isEmpty() || ((Number) rows.getFirst().get("user_id")).longValue() != userId) {
                throw new BusinessException("AI40303", "工具不属于当前用户会话");
            }
            var tool = rows.getFirst();
            if (!"PENDING".equals(tool.get("status"))) throw new BusinessException("AI40902", "工具已进入审批流程");
            ToolPolicy.authorize(String.valueOf(tool.get("tool_name")), arguments(tool), userId, true);
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ai_confirmation(id,tool_call_id,confirmation_token,user_id,expires_at,status,created_at,execution_enabled)
                    VALUES(?,?,?,?,TIMESTAMPADD(SECOND,600,CURRENT_TIMESTAMP),'PENDING',CURRENT_TIMESTAMP,TRUE)
                    """, DistributedId.next(), toolId, token, userId);
            jdbc.update("UPDATE ai_tool_call SET status='AWAITING_CONFIRMATION' WHERE id=?", toolId);
            return token;
        });
    }

    Map<String, Object> confirm(String token, long userId, String authorization) {
        var row = tx.execute(s -> {
            var owned = lockedApproval(token, userId);
            expirePending(owned);
            return owned;
        });
        // User-scoped remote reads run outside the local approval transaction. A service
        // failure leaves PENDING intact so the owner can retry without storing a JWT.
        if ("PENDING".equals(row.get("status"))) {
            tools.validateDraftResources(String.valueOf(row.get("tool_name")), arguments(row), userId, authorization);
        }
        return transition(token, userId, true);
    }

    Map<String, Object> cancel(String token, long userId) {
        return transition(token, userId, false);
    }

    Map<String, Object> status(String token, long userId) {
        return tx.execute(s -> {
            var row = lockedApproval(token, userId);
            expirePending(row);
            return response(token, String.valueOf(row.get("status")), (String) row.get("draft_no"));
        });
    }

    private Map<String, Object> transition(String token, long userId, boolean approve) {
        return tx.execute(s -> {
            var row = lockedApproval(token, userId);
            expirePending(row);
            String current = String.valueOf(row.get("status"));
            if ("PENDING".equals(current)) {
                current = approve ? "CONFIRMED" : "CANCELLED";
                setStatus(row, current, null);
            }
            return response(token, current, (String) row.get("draft_no"));
        });
    }

    private Map<String, Object> lockedApproval(String token, long userId) {
        var rows = jdbc.queryForList("""
                SELECT c.id,c.tool_call_id,c.user_id,c.status,c.draft_no,c.execution_enabled,
                  c.expires_at<=CURRENT_TIMESTAMP AS expired,t.tool_name,t.arguments_json,v.user_id AS conversation_user_id
                FROM ai_confirmation c JOIN ai_tool_call t ON t.id=c.tool_call_id
                JOIN ai_conversation v ON v.id=t.conversation_id
                WHERE c.confirmation_token=? FOR UPDATE
                """, token);
        if (rows.isEmpty()) throw new BusinessException("AI40402", "确认令牌不存在");
        var row = rows.getFirst();
        if (((Number) row.get("user_id")).longValue() != userId
                || ((Number) row.get("conversation_user_id")).longValue() != userId) {
            throw new BusinessException("AI40303", "确认令牌不属于当前用户会话");
        }
        return row;
    }

    private void expirePending(Map<String, Object> row) {
        if ("PENDING".equals(row.get("status"))
                && (truthy(row.get("expired")) || !truthy(row.get("execution_enabled")))) {
            setStatus(row, "EXPIRED", null);
            row.put("status", "EXPIRED");
        }
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
                  c.expires_at<=CURRENT_TIMESTAMP AS expired,t.tool_name,t.arguments_json,
                  t.status AS tool_status,v.user_id AS conversation_user_id
                FROM ai_confirmation c JOIN ai_tool_call t ON t.id=c.tool_call_id
                JOIN ai_conversation v ON v.id=t.conversation_id
                WHERE c.id=? FOR UPDATE
                """, id);
        String status = String.valueOf(approval.get("status"));
        if ("PENDING".equals(status) && (truthy(approval.get("expired")) || !truthy(approval.get("execution_enabled")))) {
            setStatus(approval, "EXPIRED", null);
        } else if ("CONFIRMED".equals(status) && truthy(approval.get("execution_enabled"))) {
            try {
                if (!"CONFIRMED".equals(approval.get("tool_status"))
                        || ((Number) approval.get("user_id")).longValue() != ((Number) approval.get("conversation_user_id")).longValue()) {
                    throw new BusinessException("AI40303", "工具与审批所属会话不一致");
                }
                var args = arguments(approval);
                String draft = tools.executeApprovedDraft(String.valueOf(approval.get("tool_name")), args,
                        ((Number) approval.get("user_id")).longValue(), id);
                setStatus(approval, "EXECUTED", draft);
            } catch (BusinessException e) {
                setStatus(approval, "DENIED", null);
            }
        }
    }

    private Map<String, Object> arguments(Map<String, Object> row) {
        try {
            return mapper.readValue(String.valueOf(row.get("arguments_json")), new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException("AI40014", "审批参数无效");
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
