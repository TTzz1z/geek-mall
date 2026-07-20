package com.geekmall.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session-level slots stored in {@code ai_conversation.context_json.slots}.
 * Runtime keys (last_search_*) are owned by the orchestrator, not the LLM.
 */
public final class ConversationSlots {
    public static final String KEY_SLOTS = "slots";
    public static final String LAST_SEARCH_SPU_IDS = "last_search_spu_ids";
    public static final String LAST_COMPARE_SPU_IDS = "last_compare_spu_ids";
    public static final String LAST_SKU_ID = "last_sku_id";
    public static final String LAST_DETAIL_SPU_ID = "last_detail_spu_id";
    public static final String FOCUS_ORDER_NO = "focus_order_no";

    private static final Pattern ORDINAL = Pattern.compile(
            "第\\s*([一二三四五六七八九十]|[1-9]|10)\\s*(?:款|个|件|种|只)?|"
                    + "(?:选|要|买|加购)?\\s*([一二三四五六七八九十]|[1-9])\\s*(?:款|个|件)");

    private ConversationSlots() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return new LinkedHashMap<>();
        Object slots = context.get(KEY_SLOTS);
        if (slots instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    /** Merge planner slots onto prior; keep runtime memory keys from prior. */
    public static Map<String, Object> mergePlan(Map<String, Object> prior, Map<String, Object> fromPlan) {
        Map<String, Object> out = new LinkedHashMap<>(prior == null ? Map.of() : prior);
        if (fromPlan == null) return out;
        for (var e : fromPlan.entrySet()) {
            String k = e.getKey();
            if (isRuntimeKey(k)) continue;
            if (e.getValue() == null) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    public static boolean isRuntimeKey(String key) {
        return LAST_SEARCH_SPU_IDS.equals(key)
                || LAST_COMPARE_SPU_IDS.equals(key)
                || LAST_SKU_ID.equals(key)
                || LAST_DETAIL_SPU_ID.equals(key)
                || FOCUS_ORDER_NO.equals(key);
    }

    /** Update memory from successful tool results in this turn. */
    @SuppressWarnings("unchecked")
    public static void applyToolResults(Map<String, Object> slots, List<Map<String, Object>> toolResults) {
        if (slots == null || toolResults == null) return;
        for (Map<String, Object> tr : toolResults) {
            if (!"SUCCESS".equals(tr.get("status"))) continue;
            String name = String.valueOf(tr.get("toolName"));
            Object result = tr.get("result");
            if (!(result instanceof Map<?, ?> rm)) continue;
            Map<String, Object> res = (Map<String, Object>) rm;
            switch (name) {
                case "search_products" -> {
                    List<Long> ids = extractSearchSpuIds(res);
                    if (!ids.isEmpty()) slots.put(LAST_SEARCH_SPU_IDS, ids);
                }
                case "compare_products" -> {
                    Object args = tr.get("arguments");
                    if (args instanceof Map<?, ?> am) {
                        List<Long> ids = asLongList(am.get("spuIds"));
                        if (!ids.isEmpty()) slots.put(LAST_COMPARE_SPU_IDS, ids);
                    }
                }
                case "get_product_detail" -> {
                    Object data = res.get("data");
                    if (data instanceof Map<?, ?> dm) {
                        Object spuId = dm.get("id");
                        if (spuId == null) spuId = dm.get("spuId");
                        if (spuId instanceof Number n) slots.put(LAST_DETAIL_SPU_ID, n.longValue());
                        Object skus = dm.get("skus");
                        if (skus instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> sku) {
                            Object sid = sku.get("id");
                            if (sid instanceof Number n) slots.put(LAST_SKU_ID, n.longValue());
                        }
                    }
                }
                case "add_cart_item" -> {
                    Object args = tr.get("arguments");
                    if (args instanceof Map<?, ?> am && am.get("skuId") instanceof Number n) {
                        slots.put(LAST_SKU_ID, n.longValue());
                    }
                }
                default -> {
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> extractSearchSpuIds(Map<String, Object> res) {
        Object data = res.get("data");
        Object items = null;
        if (data instanceof Map<?, ?> dm) items = dm.get("items");
        else if (data instanceof List<?> list) items = list;
        if (!(items instanceof List<?> list)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object id = m.get("id");
            if (id == null) id = m.get("spuId");
            if (id instanceof Number n) ids.add(n.longValue());
        }
        return ids;
    }

    public static List<Long> asLongList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n) out.add(n.longValue());
                else if (o != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(o)));
                    } catch (Exception ignored) {
                    }
                }
            }
            return out;
        }
        return List.of();
    }

    /** 0-based index; default 0 when 「这款」or no ordinal. */
    public static int ordinalFromMessage(String message) {
        if (message == null || message.isBlank()) return 0;
        Matcher m = ORDINAL.matcher(message);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            return Math.max(0, cnOrDigit(g) - 1);
        }
        if (message.contains("最后一") || message.contains("最后一个")) return Integer.MAX_VALUE;
        return 0;
    }

    private static int cnOrDigit(String s) {
        if (s == null) return 1;
        return switch (s) {
            case "一", "1" -> 1;
            case "二", "两", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            case "六", "6" -> 6;
            case "七", "7" -> 7;
            case "八", "8" -> 8;
            case "九", "9" -> 9;
            case "十", "10" -> 10;
            default -> {
                try {
                    yield Integer.parseInt(s);
                } catch (Exception e) {
                    yield 1;
                }
            }
        };
    }

    public static boolean isCartFollowUp(String message) {
        if (message == null || message.isBlank()) return false;
        String m = message;
        String lower = m.toLowerCase(Locale.ROOT);
        boolean cart = containsAny(m, "加购", "加入购物车", "加到购物车", "加进购物车")
                || lower.contains("add to cart") || lower.contains("add_cart");
        if (!cart) return false;
        if (containsAny(m, "再搜", "重新搜", "换一批", "还有没有", "重新找")) return false;
        boolean refers = ORDINAL.matcher(m).find()
                || containsAny(m, "这款", "那个", "刚才", "上面", "推荐的", "对比的");
        boolean freshBrowse = containsAny(m, "帮我找", "推荐几款", "有没有", "搜一下", "对比一下")
                && containsAny(m, "笔记本", "手机", "耳机", "键盘", "充电器", "手环", "显示器");
        return refers || !freshBrowse;
    }

    public static Long pickSpuId(List<Long> ids, int ordinal) {
        if (ids == null || ids.isEmpty()) return null;
        int idx = ordinal;
        if (idx == Integer.MAX_VALUE) idx = ids.size() - 1;
        if (idx < 0 || idx >= ids.size()) return null;
        return ids.get(idx);
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }
}
