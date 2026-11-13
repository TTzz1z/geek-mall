package com.geekmall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ToolPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, Object> ID = Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE);
    private static final Map<String, Object> MONEY = Map.of("type", "number", "minimum", 0, "maximum", 10000000);
    private static final Map<String, Object> FLAG = Map.of("const", true);
    private static final Map<String, Object> INDEX = Map.of("type", "integer", "minimum", 0, "maximum", 9);
    private static final Map<String, Object> QUANTITY = Map.of("type", "integer", "minimum", 1, "maximum", 99);
    private static final Map<String, Object> PRODUCT_IDS = Map.of("type", "array", "items", ID,
            "minItems", 2, "maxItems", 5, "uniqueItems", true);
    private static final Map<String, Object> ORDER = Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$");
    private static final Map<String, Object> PATH = Map.of("type", "string", "pattern",
            "^/(?:|cart|search|compare|service|user/(?:orders|aftersales|addresses)|product/[0-9]{1,19})$");
    private static final Set<String> BLOCKED = Set.of("submit_order", "pay_order", "formal_refund", "execute_sql",
            "admin_audit_approve", "delete_business_data", "finance_payout");
    private static final Map<String, Tool> REGISTRY = Map.ofEntries(
            entry("search_products", RiskLevel.L0, "搜索在售商品，遵循用户预算",
                    object(Map.of("keyword", text(160, 0), "categoryId", ID, "budget", MONEY, "priceMin", MONEY,
                            "priceMax", MONEY, "budgetMode", choices("NONE", "HARD_MAX", "AROUND")))),
            resolved("get_product_detail", "获取商品详情", RiskLevel.L0, "spuId", ID,
                    Map.of("resolveFromSearch", FLAG, "searchIndex", INDEX), "resolveFromSearch", Map.of()),
            resolved("compare_products", "对比商品", RiskLevel.L0, "spuIds", PRODUCT_IDS,
                    Map.of("resolveFromSearch", FLAG), "resolveFromSearch", Map.of()),
            entry("get_product_knowledge", RiskLevel.L0, "检索商品知识", object(Map.of("query", text(2000, 1)), "query")),
            entry("navigate_to_page", RiskLevel.L0, "返回商城站内路径", object(Map.of("path", PATH), "path")),
            entry("get_cart", RiskLevel.L1, "查询本人购物车", object(Map.of())),
            entry("get_user_orders", RiskLevel.L1, "查询本人订单", object(Map.of())),
            entry("get_order_detail", RiskLevel.L1, "查询本人订单详情", object(Map.of("orderNo", ORDER), "orderNo")),
            entry("get_logistics", RiskLevel.L1, "查询本人订单物流", object(Map.of("orderNo", ORDER, "packageNo", ORDER), "orderNo")),
            entry("query_operation_metrics", RiskLevel.L1, "查询运营指标", object(Map.of())),
            resolved("add_cart_item", "加购", RiskLevel.L2, "skuId", ID,
                    Map.of("resolveFromDetail", FLAG), "resolveFromDetail", Map.of("quantity", QUANTITY)),
            entry("create_aftersale_draft", RiskLevel.L2, "生成售后草稿",
                    object(Map.of("orderNo", ORDER, "orderItemId", ID, "reason", text(500, 1),
                            "type", choices("REFUND_ONLY", "RETURN_REFUND", "EXCHANGE", "REPAIR")))),
            entry("generate_product_draft", RiskLevel.L2, "生成商品录入草稿", object(Map.of("source", text(2000, 1)))),
            entry("create_ticket_draft", RiskLevel.L2, "生成客服工单草稿",
                    object(Map.of("title", text(100, 1), "description", text(1000, 1)))),
            entry("cancel_paid_order_draft", RiskLevel.L3, "确认后保存取消订单草稿",
                    object(Map.of("orderNo", ORDER, "reason", text(500, 1)), "orderNo")),
            entry("modify_address_draft", RiskLevel.L3, "确认后保存改地址草稿",
                    object(Map.of("orderNo", ORDER, "addressId", ID), "orderNo", "addressId"))
    );

    private ToolPolicy() {}

    public static RiskLevel riskOf(String name) {
        Tool tool = REGISTRY.get(key(name));
        return tool == null ? RiskLevel.L4 : tool.risk();
    }

    public static boolean isRegistered(String name) {
        return REGISTRY.containsKey(key(name)) || BLOCKED.contains(key(name));
    }

    public static boolean isForbidden(String name) {
        return riskOf(name).forbidden();
    }

    public static Set<String> allowedTools() {
        return REGISTRY.keySet();
    }

    static void validatePlan(String name, Map<String, Object> arguments) {
        validate(name, arguments, true);
    }

    static void authorize(String name, Map<String, Object> args, long userId, boolean confirmed) {
        validate(name, args, false);
        var risk = riskOf(name);
        if (risk.requiresLogin() && userId <= 0) throw new BusinessException("AI40101", "该工具需要登录");
        if (risk.requiresConfirmation() != confirmed) throw new BusinessException("AI40305", "工具执行需要匹配的用户确认");
    }

    private static void validate(String name, Map<String, Object> args, boolean plan) {
        if (BLOCKED.contains(key(name))) throw new BusinessException("AI40301", "该工具禁止由 AI 执行");
        Tool tool = REGISTRY.get(key(name));
        if (tool == null) throw new BusinessException("AI40401", "未注册工具");
        JsonNode node = JSON.valueToTree(args == null ? Map.of() : args);
        if (!(plan ? tool.plan() : tool.execution()).validate(node).isEmpty()) {
            throw new BusinessException("AI40014", "工具参数不符合 Schema");
        }
        if (node.has("priceMin") && node.has("priceMax")
                && node.get("priceMin").decimalValue().compareTo(node.get("priceMax").decimalValue()) > 0) {
            throw new BusinessException("AI40014", "价格范围无效");
        }
    }

    public static String schemasForPrompt() {
        return JSON.valueToTree(REGISTRY.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Map.of("name", e.getKey(), "risk", e.getValue().risk(), "description", e.getValue().description(),
                        "parameters", e.getValue().schema()))
                .collect(Collectors.toList())).toString();
    }

    private static Map.Entry<String, Tool> entry(String name, RiskLevel risk, String description, Map<String, Object> schema) {
        return entry(name, risk, description, schema, schema);
    }

    private static Map.Entry<String, Tool> entry(String name, RiskLevel risk, String description,
                                                  Map<String, Object> plan, Map<String, Object> execution) {
        return Map.entry(name, new Tool(risk, description, plan, FACTORY.getSchema(JSON.valueToTree(plan)),
                FACTORY.getSchema(JSON.valueToTree(execution))));
    }

    private static Map.Entry<String, Tool> resolved(String name, String description, RiskLevel risk,
                                                   String field, Object fieldSchema, Map<String, Object> references,
                                                   String resolutionField, Map<String, Object> extras) {
        var direct = new LinkedHashMap<>(extras);
        direct.put(field, fieldSchema);
        var indirect = new LinkedHashMap<>(extras);
        indirect.putAll(references);
        var execution = object(direct, field);
        return entry(name, risk, description, Map.of("oneOf", java.util.List.of(execution,
                object(indirect, resolutionField))), execution);
    }

    private static Map<String, Object> object(Map<String, Object> fields, String... required) {
        return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> text(int max, int min) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }

    private static Map<String, Object> choices(String... values) {
        return Map.of("type", "string", "enum", values);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private record Tool(RiskLevel risk, String description, Map<String, Object> schema, JsonSchema plan, JsonSchema execution) {}
}
