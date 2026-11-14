package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/** MySQL session ownership serializes a conversation and is released on process death. */
@Component
class TurnStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ThreadLocal<Execution> current = new ThreadLocal<>();
    private final java.util.concurrent.Semaphore ownershipSlots;

    TurnStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        int poolSize = jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource pool
                ? pool.getMaximumPoolSize() : 10;
        // Named locks hold a connection while business checkpoints borrow another one.
        // Reserve pool capacity so a burst of distinct conversations cannot deadlock the pool.
        ownershipSlots = new java.util.concurrent.Semaphore(Math.max(1,Math.min(4,poolSize / 2)));
    }

    Map<String, Object> accept(Map<String, Object> conversation, long userId, String requestId,
                               String message, String scene) {
        String key = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        if (key.length() > 100 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new BusinessException("AI40014", "requestId 格式无效");
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(Arrays.asList(message, scene)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        jdbc.update("""
                INSERT INTO ai_turn(id,conversation_id,user_id,request_id,request_digest,status,user_message_id,assistant_message_id,context_json)
                VALUES(?,?,?,?,?,'ACCEPTED',?,?,?) ON DUPLICATE KEY UPDATE id=id
                """, UUID.randomUUID().toString(), conversation.get("id"), userId, key, digest,
                DistributedId.next(), DistributedId.next(), json(conversation));
        var row = jdbc.queryForMap("SELECT * FROM ai_turn WHERE user_id=? AND conversation_id=? AND request_id=?",
                userId, conversation.get("id"), key);
        if (!digest.equals(row.get("request_digest"))) {
            throw new BusinessException("AI40903", "requestId 已用于不同内容");
        }
        return row;
    }

    Map<String, Object> execute(Map<String, Object> accepted, Supplier<Map<String, Object>> action) {
        if (!ownershipSlots.tryAcquire()) throw new BusinessException("AI50314","AI 执行繁忙，请稍后重试");
        try {
            return executeOwned(accepted,action);
        } finally {
            ownershipSlots.release();
        }
    }

    private Map<String, Object> executeOwned(Map<String, Object> accepted, Supplier<Map<String, Object>> action) {
        String turnId = String.valueOf(accepted.get("id"));
        String lockName = "ai-conversation:" + accepted.get("conversation_id");
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            boolean owned = false;
            try {
                try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                    statement.setString(1, lockName);
                    try (var result = statement.executeQuery()) {
                        owned = result.next() && result.getInt(1) == 1;
                    }
                }
                if (!owned) {
                    var existing = jdbc.queryForMap("SELECT * FROM ai_turn WHERE id=?",turnId);
                    if (existing.get("result_json") != null) return parse(existing.get("result_json"));
                    if ("RUNNING".equals(existing.get("status"))) {
                        return new LinkedHashMap<>(Map.of("status","RUNNING","turnId",turnId,
                                "requestId",existing.get("request_id")));
                    }
                    throw new BusinessException("AI40904", "会话已有执行中的请求，请稍后使用相同 requestId 重试");
                }
                var row = jdbc.queryForMap("SELECT * FROM ai_turn WHERE id=?", turnId);
                if (row.get("result_json") != null) return parse(row.get("result_json"));
                Integer unfinished = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM ai_turn WHERE conversation_id=? AND id<>?
                          AND plan_json IS NOT NULL AND result_json IS NULL
                        """, Integer.class, row.get("conversation_id"), turnId);
                if (unfinished != null && unfinished > 0) {
                    throw new BusinessException("AI40904", "上一请求尚未完成，请使用原 requestId 重试恢复");
                }
                current.set(new Execution(turnId, row, connection, lockName));
                jdbc.update("UPDATE ai_turn SET status='RUNNING',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", turnId);
                try {
                    var result = action.get();
                    checkOwnership();
                    result.put("turnId", turnId);
                    result.put("requestId", row.get("request_id"));
                    result.putIfAbsent("status", "COMPLETED");
                    jdbc.update("UPDATE ai_turn SET status=?,result_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            "CANCELLED".equals(result.get("status")) ? "CANCELLED" : "COMPLETED", json(result), turnId);
                    return result;
                } catch (RuntimeException error) {
                    checkOwnership();
                    if (error instanceof java.util.concurrent.CancellationException) {
                        saveCancellation(row);
                    } else {
                        jdbc.update("UPDATE ai_turn SET status='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",turnId);
                    }
                    throw error;
                } finally {
                    current.remove();
                }
            } finally {
                if (owned) {
                    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        statement.setString(1, lockName);
                        statement.execute();
                    }
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Turn ownership unavailable", error);
        }
    }

    Map<String,Object> abandon(String conversationNo, String turnId, long userId) {
        var rows = jdbc.queryForList("""
                SELECT t.* FROM ai_turn t JOIN ai_conversation c ON c.id=t.conversation_id
                WHERE t.id=? AND c.conversation_no=? AND t.user_id=? AND c.user_id=?
                """, turnId, conversationNo, userId, userId);
        if (rows.isEmpty()) throw new BusinessException("AI40302","无权取消该请求");
        var row = rows.getFirst();
        var result = execute(row, () -> cancellationResult(row));
        if ("RUNNING".equals(result.get("status"))) {
            throw new BusinessException("AI40904","请求仍在执行，尚未取消");
        }
        return result;
    }

    private Map<String, Object> cancellationResult(Map<String, Object> row) {
        var result = new LinkedHashMap<String,Object>();
        result.put("status", "CANCELLED");
        result.put("turnId", row.get("id"));
        result.put("requestId", row.get("request_id"));
        result.put("steps", jdbc.queryForList("""
                SELECT step_no,tool_call_id,result_json FROM ai_turn_step WHERE turn_id=? ORDER BY step_no
                """, row.get("id")));
        result.put("message", "生成已取消；已提交的业务步骤保留，请在业务页面核对");
        return result;
    }

    private void saveCancellation(Map<String, Object> row) {
        jdbc.update("UPDATE ai_turn SET status='CANCELLED',result_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                json(cancellationResult(row)), row.get("id"));
    }

    void checkOwnership() {
        var execution = current.get();
        if (execution == null) return;
        try (var statement = execution.connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
            statement.setString(1, execution.lockName);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Turn ownership lost");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Turn ownership lost", error);
        }
    }

    Map<String, Object> snapshot(Map<String, Object> fallback) {
        var execution = current.get();
        if (execution == null) return fallback;
        checkOwnership();
        // Refresh only the first attempt after acquiring ownership; retries use the prepared context.
        if (execution.row.get("plan_json") == null) {
            var fresh = jdbc.queryForMap("SELECT * FROM ai_conversation WHERE id=?", execution.row.get("conversation_id"));
            jdbc.update("UPDATE ai_turn SET context_json=? WHERE id=?", json(fresh), execution.id);
            execution.row.put("context_json", json(fresh));
        }
        return parse(execution.row.get("context_json"));
    }

    long userMessageId() {
        return current.get() == null ? DistributedId.next()
                : ((Number) current.get().row.get("user_message_id")).longValue();
    }

    long assistantMessageId() {
        return current.get() == null ? DistributedId.next()
                : ((Number) current.get().row.get("assistant_message_id")).longValue();
    }

    Map<String, Object> plan() {
        return current.get() == null || current.get().row.get("plan_json") == null
                ? null : parse(current.get().row.get("plan_json"));
    }

    void savePlan(Map<String, Object> plan) {
        checkOwnership();
        if (current.get() != null) {
            jdbc.update("UPDATE ai_turn SET plan_json=? WHERE id=?", json(plan), current.get().id);
        }
    }

    Step nextStep() {
        var execution = current.get();
        if (execution == null) return new Step(null, 0, DistributedId.next(), null, null);
        checkOwnership();
        int number = execution.next++;
        jdbc.update("INSERT INTO ai_turn_step(turn_id,step_no,tool_call_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE turn_id=turn_id",
                execution.id, number, DistributedId.next());
        var row = jdbc.queryForMap("SELECT * FROM ai_turn_step WHERE turn_id=? AND step_no=?", execution.id, number);
        return new Step(execution.id, number, ((Number) row.get("tool_call_id")).longValue(),
                row.get("result_json") == null ? null : parse(row.get("result_json")),
                row.get("arguments_json") == null ? null : parse(row.get("arguments_json")));
    }

    void prepare(Step step, Map<String, Object> arguments) {
        checkOwnership();
        if (step.turn() != null) {
            jdbc.update("UPDATE ai_turn_step SET arguments_json=? WHERE turn_id=? AND step_no=?",
                    json(arguments), step.turn(), step.number());
        }
    }

    void finish(Step step, Map<String, Object> result) {
        checkOwnership();
        if (step.turn() != null) {
            jdbc.update("UPDATE ai_turn_step SET result_json=? WHERE turn_id=? AND step_no=?",
                    json(result), step.turn(), step.number());
        }
    }

    record Step(String turn, int number, long toolId, Map<String, Object> result, Map<String, Object> arguments) {
        String key() { return turn == null ? null : turn + ":" + number; }
    }

    private static class Execution {
        final String id;
        final Map<String, Object> row;
        final Connection connection;
        final String lockName;
        int next;

        Execution(String id, Map<String, Object> row, Connection connection, String lockName) {
            this.id = id;
            this.row = row;
            this.connection = connection;
            this.lockName = lockName;
        }
    }

    Map<String, Object> parse(Object raw) {
        try {
            return mapper.readValue(String.valueOf(raw), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
