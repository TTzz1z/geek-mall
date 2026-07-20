package com.geekmall.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase C: at most one empty-search replan.
 * May swap keyword / widen AROUND range; never raises HARD_MAX priceMax.
 */
public final class EmptySearchReplanner {
    private EmptySearchReplanner() {}

    public record Replan(List<PlanEnvelope.PlannedTool> tools, String reason, Map<String, Object> meta) {}

    public static Optional<Replan> maybeReplan(List<PlanEnvelope.PlannedTool> originalPlan,
                                               List<Map<String, Object>> toolResults,
                                               Map<String, Object> slots,
                                               String message) {
        if (!searchWasEmpty(toolResults)) return Optional.empty();
        Map<String, Object> lastArgs = lastSearchArgs(toolResults);
        if (lastArgs == null) lastArgs = new LinkedHashMap<>();

        Map<String, Object> next = new LinkedHashMap<>(lastArgs);
        String mode = String.valueOf(next.getOrDefault("budgetMode",
                slots.getOrDefault("budget_mode", slots.getOrDefault("budgetMode", "NONE"))));
        Double budget = asDouble(next.get("budget"));
        if (budget == null) budget = asDouble(slots.get("budget"));

        List<String> changes = new ArrayList<>();
        applyKeywordFallback(next, message, changes);
        if ("AROUND".equalsIgnoreCase(mode) && budget != null && budget > 0) {
            long lo = Math.round(budget * 0.30);
            long hi = Math.round(budget * 1.50);
            next.put("priceMin", lo);
            next.put("priceMax", hi);
            next.put("budgetMode", "AROUND");
            next.put("budget", budget);
            changes.add("widen_AROUND_to_" + lo + "_" + hi);
        } else if ("HARD_MAX".equalsIgnoreCase(mode) && budget != null && budget > 0) {
            // Never raise priceMax above budget; may only lower priceMin for more recall.
            next.put("budgetMode", "HARD_MAX");
            next.put("budget", budget);
            next.put("priceMax", Math.round(budget));
            next.put("priceMin", Math.round(Math.max(0, budget * 0.10)));
            changes.add("HARD_MAX_keep_cap_" + Math.round(budget));
        }

        // Avoid identical args → useless second call
        if (sameSearchArgs(lastArgs, next)) {
            // Force a keyword change as last resort when category known
            Object cid = next.get("categoryId");
            if (cid instanceof Number && (next.get("keyword") == null || !"".equals(String.valueOf(next.get("keyword"))))) {
                next.put("keyword", "");
                changes.add("force_empty_keyword");
            } else {
                return Optional.empty();
            }
        }

        List<PlanEnvelope.PlannedTool> tools = new ArrayList<>();
        tools.add(new PlanEnvelope.PlannedTool("search_products", next, "replan-empty-search"));
        boolean wantCompare = originalPlan != null && originalPlan.stream()
                .anyMatch(t -> "compare_products".equals(t.name()));
        if (wantCompare) {
            tools.add(new PlanEnvelope.PlannedTool("compare_products",
                    Map.of("resolveFromSearch", true), "replan-empty-search"));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", "EMPTY_SEARCH");
        meta.put("changes", changes);
        meta.put("budgetMode", mode);
        meta.put("previousArgs", lastArgs);
        meta.put("nextArgs", next);
        return Optional.of(new Replan(tools, "EMPTY_SEARCH", meta));
    }

    static boolean searchWasEmpty(List<Map<String, Object>> toolResults) {
        boolean sawSearch = false;
        for (Map<String, Object> tr : toolResults) {
            if (!"search_products".equals(tr.get("toolName"))) continue;
            sawSearch = true;
            if ("SUCCESS".equals(tr.get("status")) && !searchItemsEmpty(tr)) {
                return false;
            }
        }
        return sawSearch;
    }

    @SuppressWarnings("unchecked")
    private static boolean searchItemsEmpty(Map<String, Object> tr) {
        Object result = tr.get("result");
        if (!(result instanceof Map<?, ?> rm)) return true;
        Object data = rm.get("data");
        Object items = null;
        if (data instanceof Map<?, ?> dm) items = dm.get("items");
        else if (data instanceof List<?> list) items = list;
        if (!(items instanceof List<?> list)) return true;
        return list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastSearchArgs(List<Map<String, Object>> toolResults) {
        for (int i = toolResults.size() - 1; i >= 0; i--) {
            Map<String, Object> tr = toolResults.get(i);
            if (!"search_products".equals(tr.get("toolName"))) continue;
            Object args = tr.get("arguments");
            if (args instanceof Map<?, ?> m) {
                return new LinkedHashMap<>((Map<String, Object>) m);
            }
        }
        return null;
    }

    private static void applyKeywordFallback(Map<String, Object> args, String message, List<String> changes) {
        String prev = args.get("keyword") == null ? "" : String.valueOf(args.get("keyword")).trim();
        Long cat = args.get("categoryId") instanceof Number n ? n.longValue() : null;
        String next = alternateKeyword(cat, prev, message);
        if (!next.equals(prev)) {
            args.put("keyword", next);
            changes.add("keyword_" + prev + "_to_" + (next.isEmpty() ? "<empty>" : next));
        }
    }

    /** Prefer emptying keyword when category is set; otherwise broaden. */
    static String alternateKeyword(Long categoryId, String previous, String message) {
        String prev = previous == null ? "" : previous.trim();
        if (categoryId != null) {
            if (!prev.isEmpty()) return "";
            return switch (categoryId.intValue()) {
                case 110 -> "Book";
                case 220 -> "Phone";
                case 510 -> "";
                case 600 -> "Band";
                case 410 -> "充电";
                case 420 -> "键盘";
                case 300 -> "MiniLED";
                default -> "";
            };
        }
        if (!prev.isEmpty() && message != null) {
            if (message.contains("笔记本") || message.contains("电脑")) return "轻薄本";
            if (message.contains("手机")) return "Phone";
            if (message.contains("耳机")) return "";
        }
        if ("数码".equals(prev)) return "";
        return prev.isEmpty() ? "数码" : "";
    }

    private static boolean sameSearchArgs(Map<String, Object> a, Map<String, Object> b) {
        return eq(a.get("keyword"), b.get("keyword"))
                && eq(a.get("categoryId"), b.get("categoryId"))
                && eq(a.get("priceMin"), b.get("priceMin"))
                && eq(a.get("priceMax"), b.get("priceMax"))
                && eq(a.get("budgetMode"), b.get("budgetMode"));
    }

    private static boolean eq(Object x, Object y) {
        if (x == null && y == null) return true;
        if (x == null || y == null) return false;
        if (x instanceof Number nx && y instanceof Number ny) {
            return Double.compare(nx.doubleValue(), ny.doubleValue()) == 0;
        }
        return String.valueOf(x).equals(String.valueOf(y));
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
