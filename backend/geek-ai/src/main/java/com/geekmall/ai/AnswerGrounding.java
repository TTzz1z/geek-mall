package com.geekmall.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase C grounding: LLM final answer must only cite SPUs/prices from tool results;
 * otherwise fall back to the grounded draft.
 */
public final class AnswerGrounding {
    private static final Pattern SPU_REF = Pattern.compile("SPU\\s*#\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_REF = Pattern.compile("¥\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final String[] HALLUCINATION_MARKERS = {
            "Air 14", "Vision Monitor", "演示商品", "示例商品 Air", "FakeBook"
    };

    private AnswerGrounding() {}

    public record Result(String text, boolean usedDraft, boolean strippedHallucination, List<String> issues) {}

    public static Result ground(String llmText, String draft, List<Map<String, Object>> toolResults) {
        String safeDraft = draft == null ? "" : draft.trim();
        if (llmText == null || llmText.isBlank() || llmText.startsWith("[模型回复]")) {
            return new Result(safeDraft, true, false, List.of("llm_missing_or_local"));
        }
        Allowed allowed = collectAllowed(toolResults);
        String text = llmText.trim();
        List<String> issues = new ArrayList<>();

        for (String marker : HALLUCINATION_MARKERS) {
            if (containsIgnoreCase(text, marker) && !containsIgnoreCase(safeDraft, marker)) {
                issues.add("hallucination:" + marker);
                return new Result(safeDraft, true, true, issues);
            }
        }

        Matcher spu = SPU_REF.matcher(text);
        Set<Long> badSpus = new LinkedHashSet<>();
        while (spu.find()) {
            long id = Long.parseLong(spu.group(1));
            if (!allowed.spuIds.isEmpty() && !allowed.spuIds.contains(id)) {
                badSpus.add(id);
            }
        }
        if (!badSpus.isEmpty()) {
            issues.add("ungrounded_spu:" + badSpus);
            return new Result(safeDraft, true, false, issues);
        }

        // If tools returned products but LLM dropped all of them and invented free-form pitch, prefer draft.
        final String groundedCandidate = text;
        if (!allowed.titles.isEmpty() && !safeDraft.isBlank()) {
            boolean mentionsAnyTitle = allowed.titles.stream().anyMatch(t -> containsIgnoreCase(groundedCandidate, t));
            boolean draftMentions = allowed.titles.stream().anyMatch(t -> containsIgnoreCase(safeDraft, t));
            if (draftMentions && !mentionsAnyTitle && groundedCandidate.length() > 40) {
                issues.add("dropped_tool_products");
                return new Result(safeDraft, true, false, issues);
            }
        }

        // Soft scrub: remove price mentions far from any allowed price (keep text otherwise)
        if (!allowed.prices.isEmpty()) {
            Matcher prices = PRICE_REF.matcher(groundedCandidate);
            StringBuffer sb = new StringBuffer();
            boolean scrubbed = false;
            while (prices.find()) {
                double p = Double.parseDouble(prices.group(1));
                if (!nearAny(p, allowed.prices, 1.0)) {
                    scrubbed = true;
                    prices.appendReplacement(sb, "¥?");
                    issues.add("ungrounded_price:" + p);
                } else {
                    prices.appendReplacement(sb, prices.group());
                }
            }
            prices.appendTail(sb);
            if (scrubbed) {
                // Too many bad prices → draft safer
                long bad = issues.stream().filter(i -> i.startsWith("ungrounded_price")).count();
                if (bad >= 2) {
                    return new Result(safeDraft, true, false, issues);
                }
                return new Result(sb.toString(), false, false, issues);
            }
        }

        return new Result(groundedCandidate, false, false, issues);
    }

    private static Allowed collectAllowed(List<Map<String, Object>> toolResults) {
        Set<Long> spus = new LinkedHashSet<>();
        Set<Double> prices = new LinkedHashSet<>();
        Set<String> titles = new LinkedHashSet<>();
        if (toolResults == null) return new Allowed(spus, prices, titles);
        for (Map<String, Object> tr : toolResults) {
            if (!"SUCCESS".equals(tr.get("status"))) continue;
            String name = String.valueOf(tr.get("toolName"));
            Object result = tr.get("result");
            Object args = tr.get("arguments");
            if (args instanceof Map<?, ?> am) {
                Object sid = am.get("spuId");
                if (sid instanceof Number n) spus.add(n.longValue());
                Object ids = am.get("spuIds");
                if (ids instanceof List<?> list) {
                    for (Object o : list) if (o instanceof Number n) spus.add(n.longValue());
                }
            }
            if (!(result instanceof Map<?, ?> rm)) continue;
            if ("search_products".equals(name)) {
                collectFromSearch(rm, spus, prices, titles);
            } else if ("compare_products".equals(name)) {
                collectFromCompare(rm, spus, prices, titles);
            } else if ("get_product_detail".equals(name)) {
                collectFromDetail(rm, spus, prices, titles);
            }
        }
        return new Allowed(spus, prices, titles);
    }

    @SuppressWarnings("unchecked")
    private static void collectFromSearch(Map<?, ?> rm, Set<Long> spus, Set<Double> prices, Set<String> titles) {
        Object data = rm.get("data");
        Object items = data instanceof Map<?, ?> dm ? dm.get("items") : data;
        if (!(items instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object id = m.get("id");
            if (id == null) id = m.get("spuId");
            if (id instanceof Number n) spus.add(n.longValue());
            addPrice(prices, m.get("minPrice"));
            addPrice(prices, m.get("maxPrice"));
            Object title = m.get("title");
            if (title != null) titles.add(String.valueOf(title));
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectFromCompare(Map<?, ?> rm, Set<Long> spus, Set<Double> prices, Set<String> titles) {
        Object items = rm.get("items");
        if (!(items instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            Object sid = row.get("spuId");
            if (sid instanceof Number n) spus.add(n.longValue());
            Object detail = row.get("detail");
            if (detail instanceof Map<?, ?> d) {
                Object id = d.get("id");
                if (id instanceof Number n) spus.add(n.longValue());
                addPrice(prices, d.get("minPrice"));
                addPrice(prices, d.get("maxPrice"));
                if (d.get("title") != null) titles.add(String.valueOf(d.get("title")));
            }
        }
    }

    private static void collectFromDetail(Map<?, ?> rm, Set<Long> spus, Set<Double> prices, Set<String> titles) {
        Object data = rm.get("data");
        if (!(data instanceof Map<?, ?> d)) return;
        Object id = d.get("id");
        if (id == null) id = d.get("spuId");
        if (id instanceof Number n) spus.add(n.longValue());
        addPrice(prices, d.get("minPrice"));
        addPrice(prices, d.get("maxPrice"));
        if (d.get("title") != null) titles.add(String.valueOf(d.get("title")));
    }

    private static void addPrice(Set<Double> prices, Object v) {
        if (v instanceof Number n) prices.add(n.doubleValue());
    }

    private static boolean nearAny(double p, Set<Double> allowed, double eps) {
        for (Double a : allowed) {
            if (a != null && Math.abs(a - p) <= eps) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null || needle.isBlank()) return false;
        return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private record Allowed(Set<Long> spuIds, Set<Double> prices, Set<String> titles) {}
}
