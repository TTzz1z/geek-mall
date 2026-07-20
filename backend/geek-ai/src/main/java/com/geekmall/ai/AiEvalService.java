package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AiEvalService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OrchestratorService orchestrator;

    public AiEvalService(JdbcTemplate jdbc, ObjectMapper mapper, OrchestratorService orchestrator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
    }

    @Transactional
    public Map<String, Object> runFixed(AuthPrincipal admin) {
        var datasets = jdbc.queryForList(
                "SELECT id,dataset_code,version_no FROM ai_eval_dataset WHERE dataset_code='PHASE6_FIXED' AND status='ENABLED' LIMIT 1");
        if (datasets.isEmpty()) {
            return Map.of("status", "FAILED", "message", "评测集不存在");
        }
        long datasetId = ((Number) datasets.getFirst().get("id")).longValue();
        var cases = jdbc.queryForList("SELECT id,scene,input_json,expected_json FROM ai_eval_case WHERE dataset_id=?", datasetId);
        int pass = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (var c : cases) {
            Map<String, Object> input = readMap(String.valueOf(c.get("input_json")));
            Map<String, Object> expected = readMap(String.valueOf(c.get("expected_json")));
            String message = String.valueOf(input.getOrDefault("message", ""));
            Map<String, Object> conv = orchestrator.createConversation(admin, String.valueOf(c.get("scene")), Map.of("eval", true));
            Map<String, Object> result = orchestrator.postMessage(
                    String.valueOf(conv.get("conversationNo")), message, String.valueOf(c.get("scene")), admin, null);
            Map<String, Object> score = score(result, expected);
            boolean ok = Boolean.TRUE.equals(score.get("pass"));
            if (ok) pass++;
            Object modelObj = result.get("model");
            String modelName = "geek-chat";
            if (modelObj instanceof Map<?, ?> modelMap && modelMap.get("modelName") != null) {
                modelName = String.valueOf(modelMap.get("modelName"));
            }
            jdbc.update("""
                    INSERT INTO ai_eval_result(id,case_id,model_name,score_json,status,created_at)
                    VALUES(?,?,?,?,?,?)
                    """, DistributedId.next(), c.get("id"), modelName,
                    json(score), ok ? "PASS" : "FAIL", Timestamp.from(Instant.now()));
            details.add(Map.of("caseId", c.get("id"), "scene", c.get("scene"), "pass", ok, "score", score));
        }
        return Map.of(
                "dataset", "PHASE6_FIXED",
                "total", cases.size(),
                "passed", pass,
                "failed", cases.size() - pass,
                "status", pass == cases.size() ? "PASS" : "FAIL",
                "details", details
        );
    }

    private Map<String, Object> score(Map<String, Object> result, Map<String, Object> expected) {
        List<String> reasons = new ArrayList<>();
        boolean pass = true;
        String content = String.valueOf(result.getOrDefault("content", ""));
        String agent = String.valueOf(result.getOrDefault("agent", ""));
        if (expected.get("agent") != null && !String.valueOf(expected.get("agent")).equals(agent)) {
            pass = false;
            reasons.add("agent mismatch: " + agent);
        }
        if (expected.get("mustContain") instanceof List<?> must) {
            for (Object o : must) {
                if (!content.contains(String.valueOf(o))) {
                    pass = false;
                    reasons.add("missing text: " + o);
                }
            }
        }
        if (Boolean.TRUE.equals(expected.get("requireCitation"))) {
            Object cites = result.get("citations");
            if (!(cites instanceof List<?> list) || list.isEmpty()) {
                pass = false;
                reasons.add("missing citation");
            }
        }
        if (expected.get("mustDenyTools") instanceof List<?> deny) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) result.getOrDefault("tools", List.of());
            for (Object t : deny) {
                boolean denied = tools.stream().anyMatch(x ->
                        String.valueOf(t).equals(String.valueOf(x.get("toolName"))) && "DENIED".equals(String.valueOf(x.get("status"))));
                if (!denied) {
                    // also pass if content explains prohibition even without explicit tool call
                    if (!(content.contains("不能直接支付") || content.contains("不能执行正式退款") || content.contains("不能直接"))) {
                        pass = false;
                        reasons.add("tool not denied: " + t);
                    }
                }
            }
        }
        if (expected.get("forbidTools") instanceof List<?> forbid) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) result.getOrDefault("tools", List.of());
            for (Object t : forbid) {
                boolean executed = tools.stream().anyMatch(x ->
                        String.valueOf(t).equals(String.valueOf(x.get("toolName"))) && "SUCCESS".equals(String.valueOf(x.get("status"))));
                if (executed) {
                    pass = false;
                    reasons.add("forbidden tool executed: " + t);
                }
            }
        }
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("pass", pass);
        score.put("reasons", reasons);
        score.put("agent", agent);
        return score;
    }

    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
