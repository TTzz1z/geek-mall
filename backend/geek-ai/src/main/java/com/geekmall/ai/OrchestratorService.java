package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class OrchestratorService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ModelGateway modelGateway;
    private final HybridRagService rag;
    private final ToolExecutor tools;
    private final PlannerGateway plannerGateway;
    private final PlanValidator planValidator;
    private final ContextPacker contextPacker;
    private final boolean replanEnabled;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public OrchestratorService(JdbcTemplate jdbc, ObjectMapper mapper, ModelGateway modelGateway,
                               HybridRagService rag, ToolExecutor tools,
                               PlannerGateway plannerGateway, PlanValidator planValidator,
                               ContextPacker contextPacker,
                               @Value("${geek.llm.replan-enabled:true}") boolean replanEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.modelGateway = modelGateway;
        this.rag = rag;
        this.tools = tools;
        this.plannerGateway = plannerGateway;
        this.planValidator = planValidator;
        this.contextPacker = contextPacker;
        this.replanEnabled = replanEnabled;
    }

    @Transactional
    public Map<String, Object> createConversation(AuthPrincipal user, String scene, Map<String, Object> context) {
        long id = DistributedId.next();
        String no = "AIC" + id;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ai_conversation(id,conversation_no,user_id,scene,status,context_json,created_at,updated_at)
                VALUES(?,?,?,?, 'ACTIVE',?,?,?)
                """, id, no, user.userId(), scene == null ? "GUIDE" : scene,
                json(context == null ? Map.of() : context), Timestamp.from(now), Timestamp.from(now));
        return Map.of("conversationNo", no, "conversationId", id, "scene", scene == null ? "GUIDE" : scene, "status", "ACTIVE");
    }

    public Map<String, Object> getConversation(String conversationNo, long userId, boolean staff) {
        Map<String, Object> conv = loadConversation(conversationNo);
        if (!staff && ((Number) conv.get("user_id")).longValue() != userId) {
            throw new BusinessException("AI40302", "无权访问该会话");
        }
        List<Map<String, Object>> messages = jdbc.queryForList("""
                SELECT id, role, content, token_count AS tokenCount, model_name AS modelName, agent_code AS agentCode, created_at AS createdAt
                FROM ai_message WHERE conversation_id=? ORDER BY created_at
                """, conv.get("id"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationNo", conv.get("conversation_no"));
        data.put("scene", conv.get("scene"));
        data.put("status", conv.get("status"));
        data.put("slots", ConversationSlots.fromContext(parseContextMap(conv.get("context_json"))));
        data.put("messages", messages);
        return data;
    }

    public SseEmitter streamMessage(String conversationNo, String message, String sceneHint,
                                    AuthPrincipal user, String authorization) {
        Map<String, Object> conv = loadConversation(conversationNo);
        if (((Number) conv.get("user_id")).longValue() != user.userId()) {
            throw new BusinessException("AI40302", "无权访问该会话");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> {
            try {
                TurnResult turn = processTurn(conv, message, sceneHint, user, authorization, events -> {
                    for (Map<String, Object> e : events) {
                        try {
                            send(emitter, String.valueOf(e.get("event")), e.get("data"));
                        } catch (IOException io) {
                            throw new IllegalStateException(io);
                        }
                    }
                });
                send(emitter, "message.complete", turn.completePayload());
                emitter.complete();
            } catch (Exception ex) {
                try {
                    send(emitter, "error", Map.of("message", ex.getMessage() == null ? "AI 处理失败" : ex.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @Transactional
    public Map<String, Object> postMessage(String conversationNo, String message, String sceneHint,
                                           AuthPrincipal user, String authorization) {
        Map<String, Object> conv = loadConversation(conversationNo);
        if (((Number) conv.get("user_id")).longValue() != user.userId()) {
            throw new BusinessException("AI40302", "无权访问该会话");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        TurnResult turn = processTurn(conv, message, sceneHint, user, authorization, events::addAll);
        Map<String, Object> data = turn.completePayload();
        data.put("events", events);
        return data;
    }

    @Transactional
    public Map<String, Object> confirmTool(String confirmationToken, AuthPrincipal user) {
        var rows = jdbc.queryForList("SELECT * FROM ai_confirmation WHERE confirmation_token=? FOR UPDATE", confirmationToken);
        if (rows.isEmpty()) throw new BusinessException("AI40402", "确认令牌不存在");
        Map<String, Object> c = rows.getFirst();
        if (((Number) c.get("user_id")).longValue() != user.userId()) {
            throw new BusinessException("AI40303", "确认令牌不属于当前用户");
        }
        if (!"PENDING".equals(String.valueOf(c.get("status")))) {
            throw new BusinessException("AI40901", "确认令牌已使用或失效");
        }
        if (((Timestamp) c.get("expires_at")).toInstant().isBefore(Instant.now())) {
            jdbc.update("UPDATE ai_confirmation SET status='EXPIRED' WHERE id=?", c.get("id"));
            throw new BusinessException("AI40902", "确认令牌已过期");
        }
        jdbc.update("UPDATE ai_confirmation SET status='CONFIRMED' WHERE id=?", c.get("id"));
        jdbc.update("UPDATE ai_tool_call SET status='CONFIRMED' WHERE id=?", c.get("tool_call_id"));
        return Map.of("confirmationToken", confirmationToken, "status", "CONFIRMED",
                "message", "已记录用户确认。正式支付/退款/提交订单仍须在业务页面完成，AI 不会代为执行。");
    }

    @Transactional
    public Map<String, Object> cancelTool(String confirmationToken, AuthPrincipal user) {
        var rows = jdbc.queryForList("SELECT * FROM ai_confirmation WHERE confirmation_token=? FOR UPDATE", confirmationToken);
        if (rows.isEmpty()) throw new BusinessException("AI40402", "确认令牌不存在");
        Map<String, Object> c = rows.getFirst();
        if (((Number) c.get("user_id")).longValue() != user.userId()) {
            throw new BusinessException("AI40303", "确认令牌不属于当前用户");
        }
        jdbc.update("UPDATE ai_confirmation SET status='CANCELLED' WHERE id=?", c.get("id"));
        jdbc.update("UPDATE ai_tool_call SET status='CANCELLED' WHERE id=?", c.get("tool_call_id"));
        return Map.of("confirmationToken", confirmationToken, "status", "CANCELLED");
    }

    @Transactional
    public TurnResult processTurn(Map<String, Object> conv, String message, String sceneHint,
                                  AuthPrincipal user, String authorization, EventSink sink) {
        long convId = ((Number) conv.get("id")).longValue();
        Instant now = Instant.now();
        Map<String, Object> contextRoot = parseContextMap(conv.get("context_json"));
        Map<String, Object> memorySlots = ConversationSlots.fromContext(contextRoot);

        long userMsgId = DistributedId.next();
        jdbc.update("INSERT INTO ai_message(id,conversation_id,role,content,token_count,token_estimated,created_at) VALUES(?,?,?,?,?,?,?)",
                userMsgId, convId, "USER", message, Math.max(1, message.length() / 2), true, Timestamp.from(now));

        ContextPacker.PackedContext packed = contextPacker.pack(convId, userMsgId, message, sceneHint, memorySlots);

        String agent;
        List<PlanEnvelope.PlannedTool> planned;
        String plannerMode = "RULE";
        String fallbackReason = null;
        Map<String, Object> plannerMeta = new LinkedHashMap<>();
        Map<String, Object> turnSlots = new LinkedHashMap<>(memorySlots);

        var llmPlan = plannerGateway.plan(message, sceneHint, convId, packed);
        if (llmPlan.isPresent()) {
            PlanEnvelope validated = planValidator.validate(llmPlan.get(), message, memorySlots);
            agent = validated.agent();
            planned = validated.toolPlan();
            turnSlots = new LinkedHashMap<>(validated.slots() == null ? memorySlots : validated.slots());
            plannerMode = "LLM";
            plannerMeta.putAll(validated.asEvent());
            if ("PLAN_CACHE_HIT".equals(llmPlan.get().fallbackReason())) {
                plannerMeta.put("cached", true);
                plannerMeta.put("cacheReason", "PLAN_CACHE_HIT");
            }
            sink.emit(List.of(event("plan.created", plannerMeta)));
        } else {
            agent = AgentRouter.route(message, sceneHint);
            planned = planTools(agent, message, memorySlots);
            // Run validator rewrite for cart follow-up / search guards on rule plans too
            PlanEnvelope ruleEnv = new PlanEnvelope(agent, 1.0, null, memorySlots, planned, true, "RULE", null);
            PlanEnvelope validated = planValidator.validate(ruleEnv, message, memorySlots);
            agent = validated.agent();
            planned = validated.toolPlan();
            turnSlots = new LinkedHashMap<>(validated.slots() == null ? memorySlots : validated.slots());
            fallbackReason = modelGateway.remoteConfigured() ? "LLM_PLAN_UNAVAILABLE" : "LLM_DISABLED";
            plannerMode = "RULE";
            PlanEnvelope fb = PlanEnvelope.ruleFallback(agent, planned, fallbackReason);
            plannerMeta.putAll(fb.asEvent());
            plannerMeta.put("slots", turnSlots);
            sink.emit(List.of(event("plan.fallback", Map.of(
                    "reason", fallbackReason,
                    "agent", agent,
                    "toolPlan", planned.stream().map(PlanEnvelope.PlannedTool::asMap).toList()
            ))));
        }

        List<Map<String, Object>> toolResults = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> confirmations = new ArrayList<>();
        boolean degraded = false;
        Map<String, Object> replanMeta = null;

        sink.emit(List.of(event("message.start", Map.of(
                "agent", agent,
                "conversationNo", conv.get("conversation_no"),
                "plannerMode", plannerMode
        ))));

        executePlannedTools(planned, toolResults, citations, confirmations, sink,
                convId, userMsgId, now, user, authorization, turnSlots, message);

        if (replanEnabled) {
            var replanOpt = EmptySearchReplanner.maybeReplan(planned, toolResults, turnSlots, message);
            if (replanOpt.isPresent()) {
                EmptySearchReplanner.Replan replan = replanOpt.get();
                replanMeta = new LinkedHashMap<>(replan.meta());
                sink.emit(List.of(event("plan.replan", replanMeta)));
                executePlannedTools(replan.tools(), toolResults, citations, confirmations, sink,
                        convId, userMsgId, now, user, authorization, turnSlots, message);
            }
        }

        ConversationSlots.applyToolResults(turnSlots, toolResults);
        contextRoot.put(ConversationSlots.KEY_SLOTS, turnSlots);
        jdbc.update("UPDATE ai_conversation SET context_json=?, updated_at=CURRENT_TIMESTAMP, scene=? WHERE id=?",
                json(contextRoot), agent, convId);

        // RAG only for knowledge/policy turns — GUIDE search results must not be mixed with unrelated PRODUCT_SPU citations.
        boolean wantRag = citations.isEmpty() && (agent.equals("PRODUCT_KNOWLEDGE") || agent.equals("AFTERSALE")
                || containsAny(message, "参数", "保修", "规格", "说明书"));
        boolean noEvidence = false;
        if (wantRag) {
            List<HybridRagService.Citation> retrieved = rag.retrieve(message, 3);
            if (retrieved.isEmpty()) {
                noEvidence = true;
                sink.emit(List.of(event("citation", Map.of("status", "NO_EVIDENCE", "message", "未检索到足够证据"))));
            } else {
                for (var c : retrieved) {
                    citations.add(c.asMap());
                    sink.emit(List.of(event("citation", c.asMap())));
                }
            }
        }

        String draft = composeAnswer(agent, message, toolResults, citations, confirmations, noEvidence);
        String answerSystem = """
                你是极客商城受控助手。根据「工具结果摘要草稿」生成最终回复。
                硬性规则：
                1) 只能使用草稿/工具结果中出现的商品名、价格、SPU#/SKU#；禁止编造（如 Air 14、Vision Monitor）。
                2) 不得宣称已支付、已退款或已提交订单。
                3) 若草稿列出了检索候选，必须保留这些候选，不要改成只追问预算。
                4) 若草稿说明未检索到商品，只允许澄清或换关键词建议，不得硬推无关品类。
                5) 若草稿说明「未检索到足够证据」，必须保留该结论，禁止输出参数型结论。
                语气可润色，事实必须 grounded。
                """;
        String answerUser = "用户原话：" + message + "\n\n工具结果摘要草稿：\n" + draft;
        ModelGateway.CompletionResult completion = modelGateway.completeAnswer(answerSystem, answerUser, convId);
        degraded = completion.degraded();
        AnswerGrounding.Result grounded = AnswerGrounding.ground(completion.text(), draft, toolResults);
        String finalText = grounded.text();
        if (grounded.usedDraft() && degraded) {
            finalText = finalText + (finalText.contains("模型已降级") ? "" : "\n\n（模型已降级）");
        }

        // stream deltas
        for (String part : chunk(finalText, 40)) {
            sink.emit(List.of(event("message.delta", Map.of("delta", part))));
        }

        long assistantMsgId = DistributedId.next();
        jdbc.update("""
                INSERT INTO ai_message(id,conversation_id,role,content,token_count,token_estimated,model_name,agent_code,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, assistantMsgId, convId, "ASSISTANT", finalText, completion.completionTokens(),
                completion.tokenUsageEstimated(), completion.modelName(), agent, Timestamp.from(Instant.now()));

        for (Map<String, Object> c : citations) {
            jdbc.update("""
                    INSERT INTO knowledge_citation(id,message_id,document_id,chunk_id,score)
                    VALUES(?,?,?,?,?)
                    """, DistributedId.next(), assistantMsgId,
                    ((Number) c.get("documentId")).longValue(),
                    ((Number) c.get("chunkId")).longValue(),
                    c.get("score"));
        }

        return new TurnResult(assistantMsgId, agent, finalText, completion, toolResults, citations, confirmations,
                degraded, noEvidence, plannerMode, fallbackReason, plannerMeta, turnSlots, replanMeta, grounded.usedDraft());
    }

    private void executePlannedTools(List<PlanEnvelope.PlannedTool> planned,
                                     List<Map<String, Object>> toolResults,
                                     List<Map<String, Object>> citations,
                                     List<Map<String, Object>> confirmations,
                                     EventSink sink,
                                     long convId, long userMsgId, Instant now,
                                     AuthPrincipal user, String authorization,
                                     Map<String, Object> turnSlots, String message) {
        for (PlanEnvelope.PlannedTool plan : planned) {
            RiskLevel risk = ToolPolicy.riskOf(plan.name());
            long toolId = DistributedId.next();
            jdbc.update("""
                    INSERT INTO ai_tool_call(id,conversation_id,message_id,tool_name,arguments_json,status,risk_level,created_at)
                    VALUES(?,?,?,?,?,'PENDING',?,?)
                    """, toolId, convId, userMsgId, plan.name(), json(plan.args()), risk.name(), Timestamp.from(now));

            sink.emit(List.of(event("tool.call", Map.of("toolCallId", toolId, "toolName", plan.name(),
                    "riskLevel", risk.name(), "arguments", plan.args()))));

            if (risk.forbidden()) {
                jdbc.update("UPDATE ai_tool_call SET status='DENIED', result_summary=? WHERE id=?",
                        "FORBIDDEN", toolId);
                Map<String, Object> denied = Map.of("toolName", plan.name(), "status", "DENIED",
                        "reason", "AI 不得直接支付、提交订单或正式退款");
                toolResults.add(denied);
                sink.emit(List.of(event("tool.result", denied)));
                continue;
            }

            if (risk.requiresConfirmation()) {
                String token = "CFT" + DistributedId.next();
                jdbc.update("""
                        INSERT INTO ai_confirmation(id,tool_call_id,confirmation_token,user_id,expires_at,status,created_at)
                        VALUES(?,?,?,?,?,'PENDING',?)
                        """, DistributedId.next(), toolId, token, user.userId(),
                        Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
                jdbc.update("UPDATE ai_tool_call SET status='AWAITING_CONFIRMATION', result_summary=? WHERE id=?",
                        "awaiting user confirmation", toolId);
                Map<String, Object> conf = Map.of(
                        "toolCallId", toolId,
                        "toolName", plan.name(),
                        "confirmationToken", token,
                        "riskLevel", risk.name(),
                        "message", "该操作需要用户确认，AI 不会直接执行");
                confirmations.add(conf);
                sink.emit(List.of(event("confirmation.required", conf)));
                continue;
            }

            try {
                Map<String, Object> resolvedArgs = enrichToolArgs(plan, toolResults, turnSlots, message);
                Map<String, Object> result = tools.execute(plan.name(), resolvedArgs, user.userId(), authorization);
                String summary = truncate(json(result), 1800);
                jdbc.update("UPDATE ai_tool_call SET status='SUCCESS', result_summary=? WHERE id=?", summary, toolId);
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("toolCallId", toolId);
                tr.put("toolName", plan.name());
                tr.put("status", "SUCCESS");
                tr.put("arguments", resolvedArgs);
                tr.put("result", result);
                toolResults.add(tr);
                sink.emit(List.of(event("tool.result", tr)));
                if (result.get("citations") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> cm = (Map<String, Object>) m;
                            citations.add(cm);
                            sink.emit(List.of(event("citation", cm)));
                        }
                    }
                }
            } catch (BusinessException ex) {
                jdbc.update("UPDATE ai_tool_call SET status='DENIED', result_summary=? WHERE id=?", ex.getMessage(), toolId);
                Map<String, Object> denied = Map.of("toolCallId", toolId, "toolName", plan.name(),
                        "status", "DENIED", "reason", ex.getMessage());
                toolResults.add(denied);
                sink.emit(List.of(event("tool.result", denied)));
            }
        }
    }

    private List<PlanEnvelope.PlannedTool> planTools(String agent, String message) {
        return planTools(agent, message, Map.of());
    }

    private List<PlanEnvelope.PlannedTool> planTools(String agent, String message, Map<String, Object> slots) {
        List<PlanEnvelope.PlannedTool> list = new ArrayList<>();
        String m = message == null ? "" : message;
        // Explicit forbidden intents still planned so policy can deny visibly.
        String lower = m.toLowerCase(Locale.ROOT);
        if (containsAny(m, "直接支付", "帮我支付", "去支付并完成", "pay now")
                || lower.contains("pay_order") || lower.contains("pay the order")) {
            list.add(new PlanEnvelope.PlannedTool("pay_order", Map.of("intent", m)));
        }
        if (containsAny(m, "正式退款", "直接退款", "帮我退款成功", "正式退")
                || lower.contains("formal_refund") || lower.contains("formal refund")
                || lower.contains("l4-money-back") || lower.contains("forbidden money tool")) {
            list.add(new PlanEnvelope.PlannedTool("formal_refund", Map.of("intent", m)));
        }
        if (containsAny(m, "提交订单", "直接下单支付") || lower.contains("submit_order")) {
            list.add(new PlanEnvelope.PlannedTool("submit_order", Map.of("intent", m)));
        }

        List<Long> lastSearch = ConversationSlots.asLongList(
                slots == null ? null : slots.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
        boolean cartFollowUp = ConversationSlots.isCartFollowUp(m) && !lastSearch.isEmpty();

        switch (agent) {
            case "GUIDE" -> {
                if (cartFollowUp) {
                    int idx = ConversationSlots.ordinalFromMessage(m);
                    list.add(new PlanEnvelope.PlannedTool("get_product_detail",
                            Map.of("resolveFromSearch", true, "searchIndex", idx)));
                    list.add(new PlanEnvelope.PlannedTool("add_cart_item",
                            Map.of("resolveFromDetail", true, "quantity", 1)));
                } else {
                    list.add(new PlanEnvelope.PlannedTool("search_products", buildSearchArgs(m)));
                    if (containsAny(m, "加购", "加入购物车", "加到购物车")
                            || lower.contains("add to cart") || lower.contains("add_cart")) {
                        list.add(new PlanEnvelope.PlannedTool("get_product_detail", Map.of("resolveFromSearch", true)));
                        list.add(new PlanEnvelope.PlannedTool("add_cart_item", Map.of("resolveFromDetail", true, "quantity", 1)));
                    }
                }
            }
            case "PRODUCT_KNOWLEDGE" -> list.add(new PlanEnvelope.PlannedTool("get_product_knowledge", Map.of("query", m)));
            case "COMPARE" -> {
                if (cartFollowUp) {
                    int idx = ConversationSlots.ordinalFromMessage(m);
                    list.add(new PlanEnvelope.PlannedTool("get_product_detail",
                            Map.of("resolveFromSearch", true, "searchIndex", idx)));
                    list.add(new PlanEnvelope.PlannedTool("add_cart_item",
                            Map.of("resolveFromDetail", true, "quantity", 1)));
                } else {
                    list.add(new PlanEnvelope.PlannedTool("search_products", buildSearchArgs(m)));
                    list.add(new PlanEnvelope.PlannedTool("compare_products", Map.of("resolveFromSearch", true)));
                }
            }
            case "ORDER_LOGISTICS" -> {
                list.add(new PlanEnvelope.PlannedTool("get_user_orders", Map.of()));
                list.add(new PlanEnvelope.PlannedTool("navigate_to_page", Map.of("path", "/user/orders")));
            }
            case "AFTERSALE" -> {
                list.add(new PlanEnvelope.PlannedTool("get_product_knowledge", Map.of("query", "售后 退款 保修")));
                if (containsAny(m, "售后草稿", "申请售后", "创建售后")
                        || lower.contains("aftersale draft") || lower.contains("create_aftersale")
                        || !containsAny(lower, "formal_refund", "forbidden money tool", "l4-money-back")) {
                    list.add(new PlanEnvelope.PlannedTool("create_aftersale_draft", Map.of("type", "REFUND_ONLY")));
                }
                list.add(new PlanEnvelope.PlannedTool("navigate_to_page", Map.of("path", "/user/aftersales")));
            }
            case "CUSTOMER_SERVICE" -> list.add(new PlanEnvelope.PlannedTool("create_ticket_draft", Map.of("title", "客服协助")));
            case "OPS_ANALYTICS" -> list.add(new PlanEnvelope.PlannedTool("query_operation_metrics", Map.of()));
            case "PRODUCT_ENTRY" -> list.add(new PlanEnvelope.PlannedTool("generate_product_draft", Map.of("source", m)));
            default -> {
            }
        }
        return list;
    }

    /** Fill tool args from prior search/detail results so demos use real catalog IDs. */
    private Map<String, Object> enrichToolArgs(PlanEnvelope.PlannedTool plan, List<Map<String, Object>> toolResults,
                                               Map<String, Object> slots, String message) {
        Map<String, Object> args = new LinkedHashMap<>(plan.args() == null ? Map.of() : plan.args());
        int searchIndex = resolveSearchIndex(args, message);

        if ("get_product_detail".equals(plan.name()) && Boolean.TRUE.equals(args.get("resolveFromSearch"))) {
            Long spuId = searchSpuIdAt(toolResults, searchIndex);
            if (spuId == null) {
                List<Long> memoryIds = ConversationSlots.asLongList(slots.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
                spuId = ConversationSlots.pickSpuId(memoryIds, searchIndex);
            }
            if (spuId == null) {
                throw new BusinessException("AI40011", "搜索未返回可查看的商品，无法拉取详情");
            }
            args.put("spuId", spuId);
            args.remove("resolveFromSearch");
            args.remove("searchIndex");
        }
        if ("compare_products".equals(plan.name()) && Boolean.TRUE.equals(args.get("resolveFromSearch"))) {
            List<Long> spuIds = searchSpuIds(toolResults, 2);
            if (spuIds.size() < 2) {
                List<Long> memoryIds = ConversationSlots.asLongList(slots.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
                if (memoryIds.size() >= 2) {
                    spuIds = memoryIds.subList(0, Math.min(2, memoryIds.size()));
                }
            }
            if (spuIds.size() < 2) {
                throw new BusinessException("AI40012", "搜索结果不足两款，无法对比");
            }
            args.put("spuIds", spuIds);
            args.remove("resolveFromSearch");
        }
        if ("add_cart_item".equals(plan.name()) && Boolean.TRUE.equals(args.get("resolveFromDetail"))) {
            Long skuId = firstDetailSkuId(toolResults);
            if (skuId == null && slots.get(ConversationSlots.LAST_SKU_ID) instanceof Number n) {
                skuId = n.longValue();
            }
            if (skuId == null) {
                throw new BusinessException("AI40013", "商品详情未返回 SKU，无法加购");
            }
            args.put("skuId", skuId);
            args.remove("resolveFromDetail");
        }
        return args;
    }

    private int resolveSearchIndex(Map<String, Object> args, String message) {
        Object raw = args.get("searchIndex");
        if (raw instanceof Number n) return Math.max(0, n.intValue());
        if (raw != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(raw)));
            } catch (Exception ignored) {
            }
        }
        return ConversationSlots.ordinalFromMessage(message);
    }

    private Long searchSpuIdAt(List<Map<String, Object>> toolResults, int index) {
        List<Long> ids = searchSpuIds(toolResults, Math.max(index + 1, 1));
        return ConversationSlots.pickSpuId(ids, index);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchItems(List<Map<String, Object>> toolResults) {
        for (Map<String, Object> tr : toolResults) {
            if (!"search_products".equals(tr.get("toolName")) || !"SUCCESS".equals(tr.get("status"))) continue;
            Object result = tr.get("result");
            if (!(result instanceof Map<?, ?> rm)) continue;
            Object data = rm.get("data");
            if (data instanceof Map<?, ?> dm) {
                Object items = dm.get("items");
                if (items instanceof List<?> list) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                    }
                    return out;
                }
            }
            if (data instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                }
                return out;
            }
        }
        return List.of();
    }

    private List<Long> searchSpuIds(List<Map<String, Object>> toolResults, int limit) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> item : searchItems(toolResults)) {
            Object id = item.get("id");
            if (id == null) id = item.get("spuId");
            if (id instanceof Number n) {
                ids.add(n.longValue());
                if (ids.size() >= limit) break;
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Long firstDetailSkuId(List<Map<String, Object>> toolResults) {
        for (int i = toolResults.size() - 1; i >= 0; i--) {
            Map<String, Object> tr = toolResults.get(i);
            if (!"get_product_detail".equals(tr.get("toolName")) || !"SUCCESS".equals(tr.get("status"))) continue;
            Object result = tr.get("result");
            if (!(result instanceof Map<?, ?> rm)) continue;
            Object data = rm.get("data");
            if (!(data instanceof Map<?, ?> dm)) continue;
            Object skus = dm.get("skus");
            if (!(skus instanceof List<?> list) || list.isEmpty()) continue;
            Object first = list.getFirst();
            if (first instanceof Map<?, ?> sku) {
                Object id = sku.get("id");
                if (id instanceof Number n) return n.longValue();
            }
        }
        return null;
    }

    private String composeAnswer(String agent, String message, List<Map<String, Object>> toolResults,
                                 List<Map<String, Object>> citations, List<Map<String, Object>> confirmations,
                                 boolean noEvidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("【智能体 ").append(agent).append("】\n");

        boolean deniedPay = toolResults.stream().anyMatch(t -> "pay_order".equals(t.get("toolName")) && "DENIED".equals(t.get("status")));
        boolean deniedRefund = toolResults.stream().anyMatch(t -> "formal_refund".equals(t.get("toolName")) && "DENIED".equals(t.get("status")));
        boolean deniedSubmit = toolResults.stream().anyMatch(t -> "submit_order".equals(t.get("toolName")) && "DENIED".equals(t.get("status")));
        if (deniedPay || deniedSubmit) {
            sb.append("我不能直接支付或提交订单。请到收银台/确认订单页由你本人完成。\n");
        }
        if (deniedRefund) {
            sb.append("我不能执行正式退款。可先生成售后草稿，再由你在售后页确认提交。\n");
        }

        switch (agent) {
            case "GUIDE" -> {
                boolean added = toolResults.stream().anyMatch(t ->
                        "add_cart_item".equals(t.get("toolName")) && "SUCCESS".equals(t.get("status")));
                if (looksIncomplete(message) && !added && !ConversationSlots.isCartFollowUp(message)) {
                    sb.append("为了更准地推荐，请补充：预算区间、主要用途（办公/游戏/影像）、屏幕尺寸偏好。\n");
                }
                List<Map<String, Object>> products = rankForIntent(searchItems(toolResults), message);
                if (products.isEmpty()) {
                    if (added) {
                        sb.append("已按上一轮检索中的指定商品加入购物车，请到购物车确认。\n");
                    } else {
                        sb.append("暂时没有检索到匹配商品，可以换个关键词再试（例如：手机、耳机、笔记本）。\n");
                    }
                } else {
                    sb.append("根据检索结果，推荐候选如下（已按品类/预算相关性筛选，来自搜索服务）：\n");
                    int i = 1;
                    for (Map<String, Object> p : products) {
                        if (i > 5) break;
                        sb.append(i++).append(") ")
                                .append(String.valueOf(p.getOrDefault("title", "商品")))
                                .append("（¥").append(String.valueOf(p.getOrDefault("minPrice", p.getOrDefault("price", "-"))))
                                .append("）");
                        if (p.get("categoryName") != null) sb.append(" · ").append(p.get("categoryName"));
                        if (p.get("id") != null) sb.append(" SPU#").append(p.get("id"));
                        sb.append('\n');
                    }
                    sb.append("你可以继续说“对比这两款”或“把第一款加入购物车”。\n");
                }
                if (added && !products.isEmpty()) {
                    sb.append("已按检索到的真实 SKU 尝试加入购物车，请到购物车确认。\n");
                }
            }
            case "PRODUCT_KNOWLEDGE" -> {
                sb.append("基于商品资料与规则检索：\n");
                if (!citations.isEmpty()) {
                    for (Map<String, Object> c : citations) {
                        Object snip = c.get("snippet");
                        if (snip != null) sb.append(snip).append('\n');
                    }
                    sb.append("引用：").append(citations.getFirst().get("documentTitle"))
                            .append(" / ").append(citations.getFirst().get("section")).append('\n');
                } else {
                    sb.append(noEvidence
                            ? "未检索到足够证据，无法给出参数结论；请查看商品详情页参数区或补充准确型号。\n"
                            : "暂无线索可引用，建议查看商品详情页参数区。\n");
                }
            }
            case "COMPARE" -> {
                sb.append("对比基于本次搜索命中的真实商品详情：\n");
                for (Map<String, Object> tr : toolResults) {
                    if (!"compare_products".equals(tr.get("toolName")) || !"SUCCESS".equals(tr.get("status"))) continue;
                    Object result = tr.get("result");
                    if (!(result instanceof Map<?, ?> rm)) continue;
                    Object items = rm.get("items");
                    if (!(items instanceof List<?> list)) continue;
                    int idx = 1;
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> row)) continue;
                        Object detail = row.get("detail");
                        String title = "商品";
                        Object price = "-";
                        if (detail instanceof Map<?, ?> d) {
                            if (d.get("title") != null) title = String.valueOf(d.get("title"));
                            if (d.get("minPrice") != null) price = d.get("minPrice");
                            else if (d.get("maxPrice") != null) price = d.get("maxPrice");
                        }
                        sb.append(idx++).append(") ").append(title)
                                .append("（¥").append(price).append("） SPU#")
                                .append(row.get("spuId")).append('\n');
                    }
                    sb.append("请以价格、规格与销量为准对比；参数来自商品服务。\n");
                }
                if (!toolResults.stream().anyMatch(t ->
                        "compare_products".equals(t.get("toolName")) && "SUCCESS".equals(t.get("status")))) {
                    boolean added = toolResults.stream().anyMatch(t ->
                            "add_cart_item".equals(t.get("toolName")) && "SUCCESS".equals(t.get("status")));
                    if (added) {
                        sb.append("已按上一轮对比/检索中的指定商品加入购物车，请到购物车确认。\n");
                    } else {
                        sb.append("本轮未能完成对比，请换个关键词后再试。\n");
                    }
                }
            }
            case "ORDER_LOGISTICS" -> sb.append("订单/物流仅可查询本人数据。如需支付请前往收银台，我不会代为扣款。\n");
            case "AFTERSALE" -> sb.append("已按规则准备售后草稿（非正式退款）。正式提交前请你在售后页确认凭证与金额。\n");
            case "CUSTOMER_SERVICE" -> sb.append("已生成客服工单草稿，必要时可升级人工，不会自动承诺赔付。\n");
            case "OPS_ANALYTICS" -> sb.append("仅返回授权经营指标摘要，不执行任意 SQL。\n");
            case "PRODUCT_ENTRY" -> sb.append("已生成商品录入草稿，需人工确认后才能提交审核。\n");
            default -> sb.append("已处理你的请求。\n");
        }

        if (!confirmations.isEmpty()) {
            sb.append("存在需确认的操作，请在界面点击确认（仍不会直接支付/退款）。\n");
        }
        if (!citations.isEmpty()) {
            sb.append("本轮引用 ").append(citations.size()).append(" 条知识切片。\n");
        }
        return sb.toString().trim();
    }

    private boolean looksIncomplete(String message) {
        if (message == null) return true;
        boolean hasBudget = message.contains("预算") || message.matches(".*\\d{3,5}.*");
        boolean hasScene = containsAny(message, "办公", "游戏", "影像", "学习", "编程");
        return !(hasBudget && hasScene);
    }

    private Map<String, Object> buildSearchArgs(String message) {
        Map<String, Object> args = new LinkedHashMap<>();
        String keyword = extractKeyword(message);
        Long categoryId = categoryIdForIntent(message, keyword);
        if (categoryId != null) {
            args.put("categoryId", categoryId);
            // Category is authoritative. Over-specific keywords (e.g. 「笔记本」) can miss catalog titles
            // like NovaBook that only sit in 轻薄本类目，导致推荐过窄或跑偏。
            args.put("keyword", softKeywordForCategory(categoryId));
        } else {
            args.put("keyword", keyword);
        }
        BudgetConstraint budget = extractBudgetConstraint(message);
        if (budget != null && budget.amount() > 0) {
            args.put("budget", budget.amount());
            args.put("budgetMode", budget.hardCap() ? "HARD_MAX" : "AROUND");
            if (budget.hardCap()) {
                // 「以内 / 不超过 / 以下」：硬上限，绝不能放出超预算商品
                args.put("priceMin", Math.round(budget.amount() * 0.35));
                args.put("priceMax", Math.round(budget.amount()));
            } else {
                // 「左右 / 大概 / 预算」：软区间
                args.put("priceMin", Math.round(budget.amount() * 0.45));
                args.put("priceMax", Math.round(budget.amount() * 1.25));
            }
        }
        return args;
    }

    private String softKeywordForCategory(long categoryId) {
        return switch ((int) categoryId) {
            case 110 -> "轻薄本";
            case 220 -> "Phone";
            case 510 -> "";
            case 600 -> "Band";
            case 410 -> "充电";
            case 420 -> "键盘";
            case 300 -> "MiniLED";
            default -> "";
        };
    }

    private String extractKeyword(String message) {
        if (message == null || message.isBlank()) return "数码";
        if (message.contains("手机")) return "手机";
        if (message.contains("耳机") || message.contains("Buds")) return "耳机";
        if (message.contains("手表") || message.contains("手环")) return "手环";
        if (message.contains("充电") || message.contains("氮化镓")) return "充电器";
        if (message.contains("键盘")) return "键盘";
        if (message.contains("显示器") || message.contains("屏幕")) return "显示器";
        if (message.contains("笔记本") || message.contains("轻薄本") || message.contains("电脑")
                || message.contains("本子")) return "笔记本";
        // Fall back to a trimmed user phrase so search tracks the query, not a fixed demo SKU set.
        String cleaned = message.replaceAll("[？?！!。，,、\\s]+", " ").trim();
        if (cleaned.length() > 24) cleaned = cleaned.substring(0, 24);
        return cleaned.isBlank() ? "数码" : cleaned;
    }

    /** Catalog category IDs aligned with merchant-product seed (110=轻薄本, 220=手机, …). */
    private Long categoryIdForIntent(String message, String keyword) {
        String m = message == null ? "" : message;
        String k = keyword == null ? "" : keyword;
        if (m.contains("笔记本") || m.contains("轻薄本") || m.contains("电脑") || "笔记本".equals(k)) return 110L;
        if (m.contains("手机") || "手机".equals(k)) return 220L;
        if (m.contains("耳机") || "耳机".equals(k)) return 510L;
        if (m.contains("手表") || m.contains("手环") || "手环".equals(k)) return 600L;
        if (m.contains("充电") || m.contains("氮化镓") || "充电器".equals(k)) return 410L;
        if (m.contains("键盘") || "键盘".equals(k)) return 420L;
        if (m.contains("显示器") || "显示器".equals(k)) return 300L;
        return null;
    }

    private BudgetConstraint extractBudgetConstraint(String message) {
        if (message == null || message.isBlank()) return null;
        // Hard cap: 8000以内 / 不超过8000 / 8000以下 / under 8000
        var hard = java.util.regex.Pattern.compile(
                "(?:不超过|不高于|低于|小于)\\s*(\\d{3,5})|(\\d{3,5})\\s*(?:元|块)?\\s*(?:以内|以下|之内)|within\\s*(\\d{3,5})|under\\s*(\\d{3,5})",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (hard.find()) {
            for (int g = 1; g <= hard.groupCount(); g++) {
                if (hard.group(g) != null) {
                    double v = Double.parseDouble(hard.group(g));
                    if (v >= 200 && v <= 50000) return new BudgetConstraint(v, true);
                }
            }
        }
        // Soft: 预算8000 / 8000左右 / 大概8000
        var soft = java.util.regex.Pattern.compile(
                "预算\\s*(\\d{3,5})|(\\d{3,5})\\s*(?:元|块)?\\s*左右|大概\\s*(\\d{3,5})|约\\s*(\\d{3,5})").matcher(message);
        if (soft.find()) {
            for (int g = 1; g <= soft.groupCount(); g++) {
                if (soft.group(g) != null) {
                    double v = Double.parseDouble(soft.group(g));
                    if (v >= 200 && v <= 50000) return new BudgetConstraint(v, false);
                }
            }
        }
        // Fallback: bare number near 预算-ish context
        if (message.contains("预算")) {
            var bare = java.util.regex.Pattern.compile("(\\d{3,5})").matcher(message);
            if (bare.find()) {
                double v = Double.parseDouble(bare.group(1));
                if (v >= 200 && v <= 50000) return new BudgetConstraint(v, false);
            }
        }
        return null;
    }

    private Double extractBudget(String message) {
        BudgetConstraint c = extractBudgetConstraint(message);
        return c == null ? null : c.amount();
    }

    /** Keep category-relevant hits and prefer prices closer to stated budget. */
    private List<Map<String, Object>> rankForIntent(List<Map<String, Object>> items, String message) {
        if (items == null || items.isEmpty()) return List.of();
        String keyword = extractKeyword(message);
        Long wantCat = categoryIdForIntent(message, keyword);
        BudgetConstraint budget = extractBudgetConstraint(message);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> p : items) {
            if (wantCat != null) {
                Object cid = p.get("categoryId");
                if (cid instanceof Number n && n.longValue() != wantCat) continue;
            }
            if (!matchesKeywordIntent(p, keyword)) continue;
            if (budget != null && budget.hardCap() && priceOf(p) > budget.amount()) continue;
            filtered.add(p);
        }
        if (filtered.isEmpty()) {
            // Soft mode may fall back; hard cap must stay empty rather than leak over-budget items.
            if (budget != null && budget.hardCap()) return List.of();
            filtered = new ArrayList<>(items);
        }
        Double target = budget == null ? null : budget.amount();
        boolean hard = budget != null && budget.hardCap();
        filtered.sort((a, b) -> {
            double pa = priceOf(a);
            double pb = priceOf(b);
            if (target != null) {
                if (!hard) {
                    boolean aOver = pa > target * 1.05;
                    boolean bOver = pb > target * 1.05;
                    if (aOver != bOver) return aOver ? 1 : -1;
                }
                int cmp = Double.compare(Math.abs(pa - target), Math.abs(pb - target));
                if (cmp != 0) return cmp;
            }
            return Double.compare(pa, pb);
        });
        return filtered;
    }

    private record BudgetConstraint(double amount, boolean hardCap) {}

    private boolean matchesKeywordIntent(Map<String, Object> p, String keyword) {
        if (keyword == null || keyword.isBlank() || "数码".equals(keyword)) return true;
        String title = String.valueOf(p.getOrDefault("title", ""));
        String cat = String.valueOf(p.getOrDefault("categoryName", ""));
        String blob = title + cat;
        return switch (keyword) {
            case "笔记本" -> {
                Object cid = p.get("categoryId");
                yield (cid instanceof Number n && n.longValue() == 110L)
                        || (containsAny(blob, "Book", "本") && !containsAny(blob, "充电", "耳机", "手环", "键盘"));
            }
            case "手机" -> containsAny(blob, "手机", "Phone") && !containsAny(blob, "充电", "耳机");
            case "耳机" -> containsAny(blob, "耳机", "Buds");
            case "手环" -> containsAny(blob, "手环", "Band", "手表");
            case "充电器" -> containsAny(blob, "充电", "氮化镓");
            case "键盘" -> containsAny(blob, "键盘");
            case "显示器" -> containsAny(blob, "显示", "MiniLED", "屏");
            default -> true;
        };
    }

    private double priceOf(Map<String, Object> p) {
        Object v = p.get("minPrice");
        if (v == null) v = p.get("price");
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ex) {
            return Double.MAX_VALUE;
        }
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    private Map<String, Object> loadConversation(String no) {
        var rows = jdbc.queryForList("SELECT * FROM ai_conversation WHERE conversation_no=?", no);
        if (rows.isEmpty()) throw new BusinessException("AI40403", "会话不存在");
        return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContextMap(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return new LinkedHashMap<>();
        try {
            Object parsed = mapper.readValue(s, Object.class);
            if (parsed instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data == null ? Map.of() : data));
    }

    private Map<String, Object> event(String name, Object data) {
        return Map.of("event", name, "data", data);
    }

    private List<String> chunk(String text, int size) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @FunctionalInterface
    private interface EventSink {
        void emit(List<Map<String, Object>> events);
    }

    public record TurnResult(long messageId, String agent, String answer, ModelGateway.CompletionResult completion,
                             List<Map<String, Object>> toolResults, List<Map<String, Object>> citations,
                             List<Map<String, Object>> confirmations, boolean degraded, boolean noEvidence,
                             String plannerMode, String fallbackReason, Map<String, Object> planner,
                             Map<String, Object> slots, Map<String, Object> replan, boolean groundedUsedDraft) {
        Map<String, Object> completePayload() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageId", messageId);
            m.put("agent", agent);
            m.put("content", answer);
            m.put("model", completion.asMap());
            m.put("tools", toolResults);
            m.put("citations", citations);
            m.put("noEvidence", noEvidence);
            m.put("confirmations", confirmations);
            m.put("degraded", degraded);
            m.put("plannerMode", plannerMode == null ? "RULE" : plannerMode);
            if (fallbackReason != null) m.put("fallbackReason", fallbackReason);
            if (planner != null && !planner.isEmpty()) m.put("planner", planner);
            m.put("slots", slots == null ? Map.of() : slots);
            if (replan != null && !replan.isEmpty()) m.put("replan", replan);
            m.put("groundedUsedDraft", groundedUsedDraft);
            return m;
        }
    }
}
