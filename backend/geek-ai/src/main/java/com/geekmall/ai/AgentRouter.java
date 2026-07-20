package com.geekmall.ai;

import java.util.Locale;

public final class AgentRouter {
    private AgentRouter() {}

    public static String route(String message, String sceneHint) {
        if (sceneHint != null && !sceneHint.isBlank()) {
            return normalize(sceneHint);
        }
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(m, "对比", "compare", "哪个更好", "差异")) return "COMPARE";
        if (containsAny(m, "订单", "物流", "包裹", "快递", "支付", "收银台", "pay_order", "order logistics")) return "ORDER_LOGISTICS";
        if (containsAny(m, "售后", "退款", "退货", "换货", "维修", "保修", "formal_refund", "formal refund", "aftersale", "l4-money-back", "forbidden money tool")) return "AFTERSALE";
        if (containsAny(m, "客服", "工单", "人工")) return "CUSTOMER_SERVICE";
        if (containsAny(m, "经营", "转化", "gmv", "大盘", "指标")) return "OPS_ANALYTICS";
        if (containsAny(m, "录入", "说明书", "生成标题", "草稿商品")) return "PRODUCT_ENTRY";
        if (containsAny(m, "参数", "内存", "续航", "说明书", "兼容", "接口", "重量")) return "PRODUCT_KNOWLEDGE";
        return "GUIDE";
    }

    private static String normalize(String scene) {
        return switch (scene.toUpperCase(Locale.ROOT)) {
            case "GUIDE", "PRODUCT_KNOWLEDGE", "COMPARE", "ORDER_LOGISTICS",
                 "AFTERSALE", "CUSTOMER_SERVICE", "OPS_ANALYTICS", "PRODUCT_ENTRY" -> scene.toUpperCase(Locale.ROOT);
            case "ORDER", "LOGISTICS" -> "ORDER_LOGISTICS";
            case "KNOWLEDGE", "PRODUCT" -> "PRODUCT_KNOWLEDGE";
            default -> "GUIDE";
        };
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
