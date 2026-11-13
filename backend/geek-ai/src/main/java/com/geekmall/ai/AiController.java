package com.geekmall.ai;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class AiController {
    private final OrchestratorService orchestrator;
    private final AiEvalService evalService;
    private final KnowledgeIngestService ingestService;
    private final RetrievalEvaluation retrievalEvaluation;
    private final JdbcTemplate jdbc;
    private final PlanCache planCache;
    private final PlannerConcurrencyGuard plannerConcurrencyGuard;
    private final String internalKey;

    AiController(OrchestratorService orchestrator, AiEvalService evalService,
                 KnowledgeIngestService ingestService, RetrievalEvaluation retrievalEvaluation, JdbcTemplate jdbc,
                 PlanCache planCache, PlannerConcurrencyGuard plannerConcurrencyGuard,
                 @Value("${geek.internal-key}") String internalKey) {
        this.orchestrator = orchestrator;
        this.evalService = evalService;
        this.ingestService = ingestService;
        this.retrievalEvaluation = retrievalEvaluation;
        this.jdbc = jdbc;
        this.planCache = planCache;
        this.plannerConcurrencyGuard = plannerConcurrencyGuard;
        this.internalKey = internalKey;
    }

    @PostMapping("/ai/conversations")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> create(@RequestBody(required = false) CreateBody body,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        return ok(orchestrator.createConversation(p, body == null ? null : body.scene(),
                body == null ? null : body.context()), r);
    }

    @GetMapping("/ai/conversations/{conversationNo}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> detail(@PathVariable String conversationNo,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        boolean staff = p.hasRole("SYSTEM_ADMIN") || p.hasRole("CS_AGENT") || p.hasRole("OPS");
        return ok(orchestrator.getConversation(conversationNo, p.userId(), staff), r);
    }

    @PostMapping("/ai/conversations/{conversationNo}/messages")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> message(@PathVariable String conversationNo,
                                             @Valid @RequestBody MessageBody body,
                                             @AuthenticationPrincipal AuthPrincipal p,
                                             HttpServletRequest r) {
        String auth = r.getHeader("Authorization");
        return ok(orchestrator.postMessage(conversationNo, body.message(), body.scene(), p, auth), r);
    }

    @GetMapping(value = "/ai/conversations/{conversationNo}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter stream(@PathVariable String conversationNo,
                      @RequestParam @NotBlank String message,
                      @RequestParam(required = false) String scene,
                      @AuthenticationPrincipal AuthPrincipal p,
                      HttpServletRequest r, HttpServletResponse response) {
        streamHeaders(response);
        return orchestrator.streamMessage(conversationNo, message, scene, p, r.getHeader("Authorization"));
    }

    @PostMapping(value = "/ai/conversations/{conversationNo}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    SseEmitter streamPost(@PathVariable String conversationNo, @Valid @RequestBody MessageBody body,
                          @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r, HttpServletResponse response) {
        streamHeaders(response);
        return orchestrator.streamMessage(conversationNo, body.message(), body.scene(), p, r.getHeader("Authorization"));
    }

    private static void streamHeaders(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
    }

    @DeleteMapping("/ai/conversations/{conversationNo}/turns/{turnId}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, String>> cancelTurn(@PathVariable String conversationNo, @PathVariable String turnId,
                                                @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        orchestrator.cancelTurn(conversationNo, turnId, p.userId());
        return ok(Map.of("status", "CANCELLED"), r);
    }

    @PostMapping("/ai/tool-calls/{confirmationToken}/confirm")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> confirm(@PathVariable String confirmationToken,
                                             @AuthenticationPrincipal AuthPrincipal p,
                                             HttpServletRequest r) {
        return ok(orchestrator.confirmTool(confirmationToken, p), r);
    }

    @GetMapping("/ai/tool-calls/{confirmationToken}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> approvalStatus(@PathVariable String confirmationToken,
                                                    @AuthenticationPrincipal AuthPrincipal p,
                                                    HttpServletRequest r) {
        return ok(orchestrator.toolApprovalStatus(confirmationToken, p), r);
    }

    @PostMapping("/ai/tool-calls/{confirmationToken}/cancel")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> cancel(@PathVariable String confirmationToken,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        return ok(orchestrator.cancelTool(confirmationToken, p), r);
    }

    @GetMapping("/admin/ai/call-logs")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> callLogs(HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT l.request_id AS requestId, l.model_name AS modelName, l.latency_ms AS latencyMs,
                       l.prompt_tokens AS promptTokens, l.completion_tokens AS completionTokens,
                       l.cost AS estimatedCost, l.cost, l.usage_estimated AS estimated, l.usage_estimated AS tokenUsageEstimated,
                       CASE WHEN l.usage_estimated THEN 'ESTIMATED' ELSE 'PROVIDER_USAGE' END AS tokenUsageSource,
                       l.status, l.detail, l.created_at AS createdAt,
                       CASE
                         WHEN l.detail LIKE 'scene=PLAN%' THEN 'PLAN'
                         WHEN l.detail LIKE 'scene=ANSWER%' THEN 'ANSWER'
                         WHEN l.detail LIKE 'scene=%' THEN SUBSTRING_INDEX(SUBSTRING_INDEX(l.detail, 'scene=', -1), ';', 1)
                         ELSE 'OTHER'
                       END AS scene,
                       EXISTS(
                         SELECT 1 FROM ai_tool_call t
                         WHERE l.conversation_id IS NOT NULL AND t.conversation_id = l.conversation_id
                       ) AS toolUsed
                FROM ai_model_call_log l
                ORDER BY l.created_at DESC
                LIMIT 100
                """), r);
    }

    @GetMapping("/admin/ai/metrics")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> metrics(HttpServletRequest r) {
        Integer calls = jdbc.queryForObject("SELECT COUNT(1) FROM ai_model_call_log", Integer.class);
        Integer success = jdbc.queryForObject("SELECT COUNT(1) FROM ai_model_call_log WHERE status='SUCCESS'", Integer.class);
        Integer tools = jdbc.queryForObject("SELECT COUNT(1) FROM ai_tool_call", Integer.class);
        Integer denied = jdbc.queryForObject("SELECT COUNT(1) FROM ai_tool_call WHERE status='DENIED'", Integer.class);
        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT COALESCE(SUM(cost),0) AS costTotal,
                       COALESCE(SUM(prompt_tokens),0) AS promptTokens,
                       COALESCE(SUM(completion_tokens),0) AS completionTokens,
                       COALESCE(AVG(latency_ms),0) AS avgLatencyMs,
                       COALESCE(SUM(CASE WHEN detail LIKE 'scene=PLAN%' OR detail LIKE '%planner%' THEN 1 ELSE 0 END),0) AS planCalls,
                       COALESCE(SUM(CASE WHEN detail LIKE 'scene=ANSWER%' THEN 1 ELSE 0 END),0) AS answerCalls,
                       COALESCE(SUM(CASE WHEN detail LIKE 'scene=PLAN%' OR detail LIKE '%planner%' THEN cost ELSE 0 END),0) AS planCost,
                       COALESCE(SUM(CASE WHEN detail LIKE 'scene=ANSWER%' THEN cost ELSE 0 END),0) AS answerCost,
                       COALESCE(SUM(CASE WHEN usage_estimated THEN prompt_tokens ELSE 0 END),0) AS estimatedPromptTokens,
                       COALESCE(SUM(CASE WHEN usage_estimated THEN completion_tokens ELSE 0 END),0) AS estimatedCompletionTokens,
                       COALESCE(SUM(CASE WHEN NOT usage_estimated THEN prompt_tokens ELSE 0 END),0) AS providerPromptTokens,
                       COALESCE(SUM(CASE WHEN NOT usage_estimated THEN completion_tokens ELSE 0 END),0) AS providerCompletionTokens
                FROM ai_model_call_log
                """);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("modelCalls", calls == null ? 0 : calls);
        out.put("modelSuccess", success == null ? 0 : success);
        out.put("toolCalls", tools == null ? 0 : tools);
        out.put("toolDenied", denied == null ? 0 : denied);
        out.put("costTotal", agg.get("costTotal"));
        out.put("estimatedCostTotal", agg.get("costTotal"));
        out.put("promptTokens", agg.get("promptTokens"));
        out.put("completionTokens", agg.get("completionTokens"));
        out.put("avgLatencyMs", Math.round(((Number) agg.get("avgLatencyMs")).doubleValue()));
        out.put("planCalls", agg.get("planCalls"));
        out.put("answerCalls", agg.get("answerCalls"));
        out.put("planCost", agg.get("planCost"));
        out.put("answerCost", agg.get("answerCost"));
        out.put("estimatedPlanCost", agg.get("planCost"));
        out.put("estimatedAnswerCost", agg.get("answerCost"));
        out.put("estimatedPromptTokens", agg.get("estimatedPromptTokens"));
        out.put("estimatedCompletionTokens", agg.get("estimatedCompletionTokens"));
        out.put("providerPromptTokens", agg.get("providerPromptTokens"));
        out.put("providerCompletionTokens", agg.get("providerCompletionTokens"));
        out.put("tokenUsageNote", "OpenAI-compatible usage is recorded when returned; otherwise token counts are estimated. Cost is always estimated.");
        out.put("planCache", planCache.stats());
        out.put("plannerConcurrency", plannerConcurrencyGuard.stats());
        return ok(out, r);
    }

    @PostMapping("/admin/ai/evaluations/run")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<Map<String, Object>> runEval(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(evalService.runFixed(p), r);
    }

    @PostMapping("/admin/ai/knowledge/evaluate")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> evaluateRetrieval(@RequestParam(defaultValue = "5") int k, HttpServletRequest r) {
        return ok(retrievalEvaluation.evaluate(k), r);
    }

    @PostMapping("/admin/ai/knowledge/ingest")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> ingest(@Valid @RequestBody IngestBody body, HttpServletRequest r) {
        return ok(ingestService.ingest(new KnowledgeIngestService.IngestRequest(
                body.kbCode(), body.sourceType(), body.sourceId(), body.title(),
                body.mimeType(), body.fileUrl(), body.content())), r);
    }

    @PostMapping("/internal/ai/knowledge/product-sync")
    ApiResponse<Map<String, Object>> productSync(@RequestHeader("X-Internal-Key") String key,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest r) {
        if (!internalKey.equals(key)) throw new BusinessException("AU40304", "内部服务凭证无效");
        @SuppressWarnings("unchecked")
        Map<String, Object> product = body.get("product") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String action = body.get("action") == null ? "UPSERT" : String.valueOf(body.get("action"));
        return ok(ingestService.syncProduct(product, action), r);
    }

    @GetMapping("/admin/ai/knowledge/products/{spuId}/status")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS','PRODUCT_ADMIN')")
    ApiResponse<Map<String, Object>> knowledgeStatus(@PathVariable long spuId, HttpServletRequest r) {
        return ok(Map.of("spuId", spuId, "activeDocs", ingestService.countActiveBySource(String.valueOf(spuId))), r);
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record CreateBody(String scene, Map<String, Object> context) {}
    record MessageBody(@NotBlank @Size(max = 2000) String message, String scene) {}
    record IngestBody(@Size(max = 64) String kbCode, @Size(max = 64) String sourceType,
                      @Size(max = 64) String sourceId, @NotBlank @Size(max = 255) String title,
                      @Size(max = 160) String mimeType, @Size(max = 500) String fileUrl,
                      @Size(max = 240000) String content) {}
}
