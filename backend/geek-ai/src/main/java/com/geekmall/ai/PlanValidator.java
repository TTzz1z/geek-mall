package com.geekmall.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PlanValidator {
    private static final Set<String> AGENTS = Set.of(
            "GUIDE", "PRODUCT_KNOWLEDGE", "COMPARE", "ORDER_LOGISTICS",
            "AFTERSALE", "CUSTOMER_SERVICE", "OPS_ANALYTICS", "PRODUCT_ENTRY");

    private final int maxToolSteps;

    public PlanValidator(@Value("${geek.llm.planner-max-tool-steps:5}") int maxToolSteps) {
        this.maxToolSteps = Math.max(1, Math.min(8, maxToolSteps));
    }

    public PlanEnvelope validate(PlanEnvelope raw, String userMessage) {
        return validate(raw, userMessage, Map.of());
    }

    public PlanEnvelope validate(PlanEnvelope raw, String userMessage, Map<String, Object> priorSlots) {
        String agent = AGENTS.contains(raw.agent()) ? raw.agent() : AgentRouter.route(userMessage, null);
        Map<String, Object> slots = ConversationSlots.mergePlan(priorSlots, raw.slots());
        normalizeBudgetSlots(slots, userMessage);

        List<PlanEnvelope.PlannedTool> cleaned = new ArrayList<>();
        List<PlanEnvelope.PlannedTool> source = raw.toolPlan() == null ? List.of() : raw.toolPlan();
        for (PlanEnvelope.PlannedTool step : source) {
            if (cleaned.size() >= maxToolSteps) break;
            if (step == null || step.name() == null || step.name().isBlank()) continue;
            String name = step.name().trim().toLowerCase(Locale.ROOT);
            if (!ToolPolicy.isRegistered(name) && !ToolPolicy.isForbidden(name)) continue;
            Map<String, Object> args = new LinkedHashMap<>(step.args() == null ? Map.of() : step.args());
            if ("search_products".equals(name)) {
                applySearchGuards(args, slots, userMessage);
            }
            if ("compare_products".equals(name) && !args.containsKey("spuIds") && !args.containsKey("resolveFromSearch")) {
                args.put("resolveFromSearch", true);
            }
            if ("get_product_detail".equals(name) && !args.containsKey("spuId") && !args.containsKey("resolveFromSearch")) {
                args.put("resolveFromSearch", true);
            }
            if ("add_cart_item".equals(name) && !args.containsKey("skuId") && !args.containsKey("resolveFromDetail")) {
                args.put("resolveFromDetail", true);
                args.putIfAbsent("quantity", 1);
            }
            cleaned.add(new PlanEnvelope.PlannedTool(name, args, step.reason()));
        }

        // Ensure visible L4 deny when user clearly asks to pay/refund/submit but planner omitted it.
        injectForbiddenIntents(cleaned, userMessage);
        rewriteCartFollowUp(cleaned, userMessage, slots);

        if (cleaned.isEmpty()) {
            // Minimal safe default for shopping talk
            if ("COMPARE".equals(agent)) {
                Map<String, Object> search = new LinkedHashMap<>();
                applySearchGuards(search, slots, userMessage);
                cleaned.add(new PlanEnvelope.PlannedTool("search_products", search, "validator-default"));
                cleaned.add(new PlanEnvelope.PlannedTool("compare_products", Map.of("resolveFromSearch", true), "validator-default"));
            } else if ("GUIDE".equals(agent) || "PRODUCT_KNOWLEDGE".equals(agent)) {
                Map<String, Object> search = new LinkedHashMap<>();
                applySearchGuards(search, slots, userMessage);
                if ("PRODUCT_KNOWLEDGE".equals(agent)) {
                    cleaned.add(new PlanEnvelope.PlannedTool("get_product_knowledge", Map.of("query", userMessage), "validator-default"));
                } else {
                    cleaned.add(new PlanEnvelope.PlannedTool("search_products", search, "validator-default"));
                }
            }
        }

        return new PlanEnvelope(agent, raw.confidence(), raw.userGoal(), slots, cleaned, raw.stop(),
                raw.plannerMode() == null ? "LLM" : raw.plannerMode(), raw.fallbackReason());
    }

    /** 「把第一款加购」：复用 last_search_spu_ids，去掉多余 search。 */
    private void rewriteCartFollowUp(List<PlanEnvelope.PlannedTool> cleaned, String message,
                                     Map<String, Object> slots) {
        if (!ConversationSlots.isCartFollowUp(message)) return;
        List<Long> prior = ConversationSlots.asLongList(slots.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
        if (prior.isEmpty()) return;
        int idx = ConversationSlots.ordinalFromMessage(message);
        cleaned.removeIf(t -> "search_products".equals(t.name()) || "compare_products".equals(t.name()));
        boolean hasDetail = cleaned.stream().anyMatch(t -> "get_product_detail".equals(t.name()));
        boolean hasCart = cleaned.stream().anyMatch(t -> "add_cart_item".equals(t.name()));
        if (!hasDetail) {
            Map<String, Object> detailArgs = new LinkedHashMap<>();
            detailArgs.put("resolveFromSearch", true);
            detailArgs.put("searchIndex", idx);
            cleaned.add(0, new PlanEnvelope.PlannedTool("get_product_detail", detailArgs, "validator-cart-followup"));
        } else {
            for (int i = 0; i < cleaned.size(); i++) {
                PlanEnvelope.PlannedTool t = cleaned.get(i);
                if (!"get_product_detail".equals(t.name())) continue;
                Map<String, Object> args = new LinkedHashMap<>(t.args() == null ? Map.of() : t.args());
                if (!args.containsKey("spuId")) {
                    args.put("resolveFromSearch", true);
                    args.putIfAbsent("searchIndex", idx);
                }
                cleaned.set(i, new PlanEnvelope.PlannedTool(t.name(), args, t.reason()));
            }
        }
        if (!hasCart) {
            cleaned.add(new PlanEnvelope.PlannedTool("add_cart_item",
                    Map.of("resolveFromDetail", true, "quantity", 1), "validator-cart-followup"));
        }
        // Cap steps after rewrite
        while (cleaned.size() > maxToolSteps) cleaned.removeLast();
    }

    private void normalizeBudgetSlots(Map<String, Object> slots, String message) {
        BudgetHint hint = detectBudget(message);
        if (hint != null) {
            slots.put("budget", hint.amount());
            slots.put("budget_mode", hint.hardCap() ? "HARD_MAX" : "AROUND");
        } else if (slots.get("budget") != null && slots.get("budget_mode") == null) {
            slots.put("budget_mode", "AROUND");
        }
        Long cat = categoryFromMessage(message);
        if (cat != null) slots.putIfAbsent("categoryId", cat);
    }

    private void applySearchGuards(Map<String, Object> args, Map<String, Object> slots, String message) {
        BudgetHint hint = detectBudget(message);
        String mode = String.valueOf(slots.getOrDefault("budget_mode",
                hint != null && hint.hardCap() ? "HARD_MAX" : slots.getOrDefault("budgetMode", "NONE")));
        Double budget = number(slots.get("budget"));
        if (budget == null && hint != null) budget = hint.amount();
        if (budget == null) budget = number(args.get("budget"));

        Long categoryId = numberLong(args.get("categoryId"));
        if (categoryId == null) categoryId = numberLong(slots.get("categoryId"));
        if (categoryId == null) categoryId = categoryFromMessage(message);
        if (categoryId != null) args.put("categoryId", categoryId);

        // When category is known, force catalog-friendly keyword. Chinese tokens like「手机」
        // miss English titles (Nova Phone…) in ES and yield empty hits.
        if (categoryId != null) {
            args.put("keyword", softKeyword(categoryId, message));
        } else if (args.get("keyword") == null || String.valueOf(args.get("keyword")).isBlank()) {
            args.put("keyword", softKeyword(null, message));
        }

        if (budget != null && budget > 0) {
            args.put("budget", budget);
            if ("HARD_MAX".equalsIgnoreCase(mode) || (hint != null && hint.hardCap())) {
                args.put("budgetMode", "HARD_MAX");
                args.put("priceMin", Math.round(budget * 0.35));
                args.put("priceMax", Math.round(budget)); // force hard cap — ignore model soft range
            } else if ("AROUND".equalsIgnoreCase(mode) || hint != null) {
                args.put("budgetMode", "AROUND");
                args.put("priceMin", Math.round(budget * 0.45));
                args.put("priceMax", Math.round(budget * 1.25));
            }
        }
    }

    private void injectForbiddenIntents(List<PlanEnvelope.PlannedTool> cleaned, String message) {
        String m = message == null ? "" : message;
        String lower = m.toLowerCase(Locale.ROOT);
        boolean hasPay = cleaned.stream().anyMatch(t -> "pay_order".equals(t.name()));
        boolean hasRefund = cleaned.stream().anyMatch(t -> "formal_refund".equals(t.name()));
        boolean hasSubmit = cleaned.stream().anyMatch(t -> "submit_order".equals(t.name()));
        if (!hasPay && (containsAny(m, "直接支付", "帮我支付", "去支付并完成") || lower.contains("pay_order"))) {
            cleaned.add(new PlanEnvelope.PlannedTool("pay_order", Map.of("intent", m), "validator-l4"));
        }
        if (!hasRefund && (containsAny(m, "正式退款", "直接退款", "帮我退款成功") || lower.contains("formal_refund"))) {
            cleaned.add(new PlanEnvelope.PlannedTool("formal_refund", Map.of("intent", m), "validator-l4"));
        }
        if (!hasSubmit && (containsAny(m, "提交订单", "直接下单支付") || lower.contains("submit_order"))) {
            cleaned.add(new PlanEnvelope.PlannedTool("submit_order", Map.of("intent", m), "validator-l4"));
        }
    }

    private String softKeyword(Long categoryId, String message) {
        if (categoryId != null) {
            return switch (categoryId.intValue()) {
                case 110 -> "轻薄本";
                case 220 -> "Phone";
                case 510 -> ""; // NovaBuds / Buds Lite：空词 + categoryId，避免 camelCase 漏召
                case 600 -> "Band";
                case 410 -> "充电";
                case 420 -> "键盘";
                case 300 -> "MiniLED";
                default -> "";
            };
        }
        if (message != null) {
            if (message.contains("笔记本") || message.contains("电脑")) return "轻薄本";
            if (message.contains("手机")) return "手机";
            if (message.contains("耳机")) return "耳机";
        }
        return "数码";
    }

    private Long categoryFromMessage(String message) {
        if (message == null) return null;
        if (message.contains("笔记本") || message.contains("轻薄本") || message.contains("电脑")) return 110L;
        if (message.contains("手机")) return 220L;
        if (message.contains("耳机")) return 510L;
        if (message.contains("手表") || message.contains("手环")) return 600L;
        if (message.contains("充电") || message.contains("氮化镓")) return 410L;
        if (message.contains("键盘")) return 420L;
        if (message.contains("显示器")) return 300L;
        return null;
    }

    private BudgetHint detectBudget(String message) {
        if (message == null || message.isBlank()) return null;
        var hard = java.util.regex.Pattern.compile(
                "(?:不超过|不高于|低于|小于)\\s*(\\d{3,5})|(\\d{3,5})\\s*(?:元|块)?\\s*(?:以内|以下|之内)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (hard.find()) {
            for (int g = 1; g <= hard.groupCount(); g++) {
                if (hard.group(g) != null) {
                    double v = Double.parseDouble(hard.group(g));
                    if (v >= 200 && v <= 50000) return new BudgetHint(v, true);
                }
            }
        }
        var soft = java.util.regex.Pattern.compile(
                "预算\\s*(\\d{3,5})|(\\d{3,5})\\s*(?:元|块)?\\s*左右|大概\\s*(\\d{3,5})|约\\s*(\\d{3,5})").matcher(message);
        if (soft.find()) {
            for (int g = 1; g <= soft.groupCount(); g++) {
                if (soft.group(g) != null) {
                    double v = Double.parseDouble(soft.group(g));
                    if (v >= 200 && v <= 50000) return new BudgetHint(v, false);
                }
            }
        }
        return null;
    }

    private static Double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static Long numberLong(Object v) {
        Double d = number(v);
        return d == null ? null : d.longValue();
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    private record BudgetHint(double amount, boolean hardCap) {}
}
