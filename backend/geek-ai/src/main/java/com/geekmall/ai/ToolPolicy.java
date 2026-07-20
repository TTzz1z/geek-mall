package com.geekmall.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ToolPolicy {
    private static final Map<String, RiskLevel> TOOLS = Map.ofEntries(
            Map.entry("search_products", RiskLevel.L0),
            Map.entry("get_product_detail", RiskLevel.L0),
            Map.entry("compare_products", RiskLevel.L0),
            Map.entry("get_product_knowledge", RiskLevel.L0),
            Map.entry("navigate_to_page", RiskLevel.L0),
            Map.entry("get_cart", RiskLevel.L1),
            Map.entry("get_user_orders", RiskLevel.L1),
            Map.entry("get_order_detail", RiskLevel.L1),
            Map.entry("get_logistics", RiskLevel.L1),
            Map.entry("query_operation_metrics", RiskLevel.L1),
            Map.entry("add_cart_item", RiskLevel.L2),
            Map.entry("create_aftersale_draft", RiskLevel.L2),
            Map.entry("generate_product_draft", RiskLevel.L2),
            Map.entry("create_ticket_draft", RiskLevel.L2),
            Map.entry("cancel_paid_order_draft", RiskLevel.L3),
            Map.entry("modify_address_draft", RiskLevel.L3),
            Map.entry("submit_order", RiskLevel.L4),
            Map.entry("pay_order", RiskLevel.L4),
            Map.entry("formal_refund", RiskLevel.L4),
            Map.entry("execute_sql", RiskLevel.L4),
            Map.entry("admin_audit_approve", RiskLevel.L4)
    );

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("search_products", "搜索在售商品。HARD_MAX 时 priceMax 必须等于用户上限。"),
            Map.entry("get_product_detail", "按 spuId 或 resolveFromSearch 取详情/SKU。"),
            Map.entry("compare_products", "对比商品；优先 resolveFromSearch=true。"),
            Map.entry("get_product_knowledge", "检索商品说明书/规则知识。"),
            Map.entry("navigate_to_page", "返回前端跳转 path。"),
            Map.entry("get_cart", "查询本人购物车。"),
            Map.entry("get_user_orders", "查询本人订单列表。"),
            Map.entry("get_order_detail", "查询订单详情，需 orderNo。"),
            Map.entry("get_logistics", "查询物流。"),
            Map.entry("query_operation_metrics", "运营指标（可能降级）。"),
            Map.entry("add_cart_item", "加购；优先 resolveFromDetail=true。"),
            Map.entry("create_aftersale_draft", "生成售后草稿，非正式退款。"),
            Map.entry("generate_product_draft", "生成商品录入草稿。"),
            Map.entry("create_ticket_draft", "生成客服工单草稿。"),
            Map.entry("cancel_paid_order_draft", "取消已支付订单草稿，需确认。"),
            Map.entry("modify_address_draft", "改地址草稿，需确认。"),
            Map.entry("submit_order", "提交订单（禁止执行，仅用于可见拒绝）。"),
            Map.entry("pay_order", "支付（禁止执行，仅用于可见拒绝）。"),
            Map.entry("formal_refund", "正式退款（禁止执行，仅用于可见拒绝）。")
    );

    private static final Set<String> FORBIDDEN = Set.of(
            "submit_order", "pay_order", "formal_refund", "execute_sql",
            "admin_audit_approve", "delete_business_data", "finance_payout");

    private ToolPolicy() {}

    public static RiskLevel riskOf(String toolName) {
        if (toolName == null) return RiskLevel.L4;
        String key = toolName.toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(key)) return RiskLevel.L4;
        return TOOLS.getOrDefault(key, RiskLevel.L4);
    }

    public static boolean isRegistered(String toolName) {
        return toolName != null && TOOLS.containsKey(toolName.toLowerCase(Locale.ROOT));
    }

    public static boolean isForbidden(String toolName) {
        return riskOf(toolName).forbidden();
    }

    public static Set<String> allowedTools() {
        return TOOLS.entrySet().stream()
                .filter(e -> !e.getValue().forbidden())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Compact tool catalog for planner prompts. */
    public static String schemasForPrompt() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : TOOLS.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("risk", e.getValue().name());
            row.put("description", DESCRIPTIONS.getOrDefault(e.getKey(), e.getKey()));
            list.add(row);
        }
        return list.toString();
    }
}
