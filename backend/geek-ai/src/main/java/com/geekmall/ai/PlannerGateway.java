package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PlannerGateway {
    private static final Logger log = LoggerFactory.getLogger(PlannerGateway.class);

    private final ModelGateway models;
    private final ObjectMapper mapper;
    private final PlanCache planCache;
    private final PlannerConcurrencyGuard concurrencyGuard;
    private final boolean plannerEnabled;
    private final int plannerTimeoutMs;

    public PlannerGateway(ModelGateway models,
                          ObjectMapper mapper,
                          PlanCache planCache,
                          PlannerConcurrencyGuard concurrencyGuard,
                          @Value("${geek.llm.planner-enabled:true}") boolean plannerEnabled,
                          @Value("${geek.llm.planner-timeout-ms:12000}") int plannerTimeoutMs) {
        this.models = models;
        this.mapper = mapper;
        this.planCache = planCache;
        this.concurrencyGuard = concurrencyGuard;
        this.plannerEnabled = plannerEnabled;
        this.plannerTimeoutMs = plannerTimeoutMs;
    }

    public Optional<PlanEnvelope> plan(String message, String sceneHint, Long conversationId) {
        return plan(message, sceneHint, conversationId, null);
    }

    public Optional<PlanEnvelope> plan(String message, String sceneHint, Long conversationId,
                                       ContextPacker.PackedContext packed) {
        if (!plannerEnabled || !models.remoteConfigured()) {
            return Optional.empty();
        }
        Map<String, Object> slotView = packed != null && packed.slots() != null ? packed.slots() : Map.of();
        Optional<PlanEnvelope> cached = planCache.get(message, sceneHint, slotView);
        if (cached.isPresent()) {
            PlanEnvelope hit = cached.get();
            return Optional.of(new PlanEnvelope(
                    hit.agent(), hit.confidence(), hit.userGoal(), hit.slots(), hit.toolPlan(),
                    hit.stop(), "LLM", "PLAN_CACHE_HIT"));
        }

        if (!concurrencyGuard.tryAcquire()) {
            log.warn("planner rejected: concurrency limit");
            return Optional.empty();
        }
        try {
            String system = """
                    你是极客商城受控导购编排器。只输出一个 JSON 对象，不要 Markdown，不要解释。
                    JSON schema:
                    {
                      "agent": "GUIDE|PRODUCT_KNOWLEDGE|COMPARE|ORDER_LOGISTICS|AFTERSALE|CUSTOMER_SERVICE|OPS_ANALYTICS|PRODUCT_ENTRY",
                      "confidence": 0.0-1.0,
                      "user_goal": "string",
                      "slots": {
                        "budget": number|null,
                        "budget_mode": "HARD_MAX|AROUND|NONE",
                        "categoryId": number|null,
                        "use_case": string|null
                      },
                      "tool_plan": [{"name":"tool","args":{},"reason":"string"}],
                      "stop": true
                    }
                    规则：
                    1) 预算含「以内/不超过/以下」→ budget_mode=HARD_MAX，search_products.priceMax 必须等于预算，禁止抬高。
                    2) 「左右/大概/预算」→ AROUND，可用约 0.45~1.25 倍区间。
                    3) 不要编造 spuId/skuId；需要加购时用 get_product_detail(resolveFromSearch=true) + add_cart_item(resolveFromDetail=true)。
                    4) 对比用 search_products + compare_products(resolveFromSearch=true)。
                    5) 用户要求直接支付/正式退款/提交订单时，把 pay_order/formal_refund/submit_order 放入 tool_plan（系统会拒绝执行）。
                    6) tool_plan 最多 5 步；优先少而准。
                    7) 多轮：若 currentSlots.last_search_spu_ids 已有结果，且用户说「把第一款/第二款加购」，不要重新 search；
                       用 get_product_detail(resolveFromSearch=true, searchIndex=0或1…) + add_cart_item(resolveFromDetail=true)。
                       searchIndex 从 0 起，对应 last_search_spu_ids 下标。可沿用 currentSlots 的 budget/categoryId。
                    8) 不要在 slots 里写入 last_search_spu_ids / last_sku_id（由系统维护）。
                    类目提示：笔记本=110，手机=220，耳机=510，手环=600，充电器=410，键盘=420，显示器=300。
                    """;
            String user = packed != null ? packed.userPrompt()
                    : "sceneHint=" + (sceneHint == null || sceneHint.isBlank() ? "auto" : sceneHint)
                    + "\nuserMessage=" + message + "\navailableTools=" + ToolPolicy.schemasForPrompt();
            ModelGateway.CompletionResult completion = models.completeJson(
                    "PLAN", system, user, conversationId, 0.1, plannerTimeoutMs);
            if (completion.degraded() || completion.text() == null || completion.text().isBlank()) {
                return Optional.empty();
            }
            PlanEnvelope parsed = parse(completion.text());
            planCache.put(message, sceneHint, slotView, parsed);
            return Optional.of(parsed);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            }
            log.warn("planner failed ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            concurrencyGuard.release();
        }
    }

    PlanEnvelope parse(String rawText) throws Exception {
        String json = extractJsonObject(rawText);
        JsonNode root = mapper.readTree(json);
        String agent = root.path("agent").asText("GUIDE").toUpperCase();
        double confidence = root.path("confidence").asDouble(0.7);
        String goal = root.path("user_goal").asText(null);
        if (goal == null) goal = root.path("userGoal").asText(null);

        Map<String, Object> slots = new LinkedHashMap<>();
        JsonNode slotsNode = root.path("slots");
        if (slotsNode.isObject()) {
            slotsNode.fields().forEachRemaining(e -> slots.put(e.getKey(), nodeValue(e.getValue())));
        }

        List<PlanEnvelope.PlannedTool> tools = new ArrayList<>();
        JsonNode plan = root.path("tool_plan");
        if (!plan.isArray()) plan = root.path("toolPlan");
        if (plan.isArray()) {
            for (JsonNode step : plan) {
                String name = step.path("name").asText(null);
                if (name == null) name = step.path("toolName").asText(null);
                if (name == null || name.isBlank()) continue;
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode argsNode = step.path("args");
                if (!argsNode.isObject()) argsNode = step.path("arguments");
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), nodeValue(e.getValue())));
                }
                String reason = step.path("reason").asText(null);
                tools.add(new PlanEnvelope.PlannedTool(name, args, reason));
            }
        }
        boolean stop = !root.has("stop") || root.path("stop").asBoolean(true);
        return new PlanEnvelope(agent, confidence, goal, slots, tools, stop, "LLM", null);
    }

    private static String extractJsonObject(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int start = t.indexOf('{');
            int end = t.lastIndexOf('}');
            if (start >= 0 && end > start) return t.substring(start, end + 1);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return t;
    }

    private static Object nodeValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt() || n.isLong()) return n.longValue();
        if (n.isNumber()) return n.asDouble();
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(x -> list.add(nodeValue(x)));
            return list;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), nodeValue(e.getValue())));
            return m;
        }
        return n.asText();
    }
}
