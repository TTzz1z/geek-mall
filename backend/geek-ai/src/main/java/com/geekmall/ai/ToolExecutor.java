package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.web.InternalRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
public class ToolExecutor {
    private final HybridRagService rag;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final RestClient productClient;
    private final RestClient tradeClient;
    private final RestClient searchClient;
    private final RestClient aftersaleClient;

    public ToolExecutor(HybridRagService rag, ObjectMapper mapper, JdbcTemplate jdbc,
                        @Value("${geek.product-service-url}") String productUrl,
                        @Value("${geek.trade-service-url}") String tradeUrl,
                        @Value("${geek.search-service-url}") String searchUrl,
                        @Value("${geek.aftersale-service-url}") String aftersaleUrl) {
        this.rag = rag;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.productClient = InternalRestClient.create(productUrl);
        this.tradeClient = InternalRestClient.create(tradeUrl);
        this.searchClient = InternalRestClient.create(searchUrl);
        this.aftersaleClient = InternalRestClient.create(aftersaleUrl);
    }

    public Map<String, Object> execute(String toolName, Map<String, Object> args, long userId, String userToken) {
        ToolPolicy.authorize(toolName, args, userId, false);
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (toolName) {
            case "search_products" -> searchProducts(a);
            case "get_product_detail" -> {
                long spuId = longVal(a.get("spuId"), 0L);
                if (spuId <= 0) throw new BusinessException("AI40002", "缺少有效 spuId");
                yield getProductDetail(spuId);
            }
            case "compare_products" -> compareProducts(a.get("spuIds"));
            case "get_product_knowledge" -> {
                var cites = rag.retrieve(str(a.get("query"), "参数"), 3);
                yield Map.of("citations", cites.stream().map(HybridRagService.Citation::asMap).toList(),
                        "evidence", rag.buildEvidence(cites));
            }
            case "navigate_to_page" -> Map.of("path", str(a.get("path"), "/"), "action", "NAVIGATE");
            case "get_cart" -> requireUser(userId, getCart(userToken));
            case "get_user_orders" -> requireUser(userId, getUserOrders(userToken));
            case "get_order_detail" -> requireUser(userId, getOrderDetail(userToken, str(a.get("orderNo"), "")));
            case "get_logistics" -> requireUser(userId, getLogistics(userToken, a));
            case "query_operation_metrics" -> Map.of(
                    "available", false,
                    "degraded", true,
                    "source", "unavailable",
                    "metrics", List.of(),
                    "note", "运营指标未接入分析服务，禁止展示虚构数值"
            );
            case "add_cart_item" -> {
                long skuId = longVal(a.get("skuId"), 0L);
                if (skuId <= 0) throw new BusinessException("AI40003", "缺少有效 skuId");
                yield requireUser(userId, addCartItem(userToken, skuId, intVal(a.get("quantity"), 1)));
            }
            case "create_aftersale_draft" -> requireUser(userId, createAftersaleDraft(userId, a));
            case "generate_product_draft" -> Map.of("status", "DRAFT", "title", "AI 生成商品草稿", "confidence", 0.72,
                    "message", "草稿需人工确认后才能提交审核");
            case "create_ticket_draft" -> requireUser(userId, saveDraft(userId, "TICKET", a,
                    Map.of("status", "DRAFT", "title", a.getOrDefault("title", "客服工单草稿"))));
            default -> throw new BusinessException("AI40401", "未实现工具: " + toolName);
        };
    }

    String executeApprovedDraft(String tool, Map<String, Object> args, long userId, long approvalId) {
        ToolPolicy.authorize(tool, args, userId, true);
        String draftType = switch (tool) {
            case "cancel_paid_order_draft" -> "CANCEL_PAID_ORDER";
            case "modify_address_draft" -> "MODIFY_ADDRESS";
            default -> throw new BusinessException("AI40305", "该工具无法通过审批执行");
        };
        String draftNo = "AICF" + approvalId;
        jdbc.update("""
                INSERT INTO ai_business_draft(id,draft_no,user_id,draft_type,status,payload_json,created_at,updated_at)
                VALUES(?,?,?,?,'DRAFT',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE draft_no=draft_no
                """, DistributedId.next(), draftNo, userId, draftType, json(args));
        return draftNo;
    }

    private Map<String, Object> searchProducts(Map<String, Object> a) {
        Long categoryId = a.get("categoryId") instanceof Number n ? n.longValue() : null;
        // Blank keyword + categoryId = category browse. Do NOT default blank to「数码」
        // (English titles like NovaBuds miss that token and return zero hits).
        String rawKeyword = a.get("keyword") == null ? null : String.valueOf(a.get("keyword")).trim();
        String keyword;
        if (rawKeyword == null || rawKeyword.isBlank()) {
            keyword = categoryId != null ? "" : "数码";
        } else {
            keyword = rawKeyword;
        }
        Double priceMin = a.get("priceMin") instanceof Number n ? n.doubleValue() : null;
        Double priceMax = a.get("priceMax") instanceof Number n ? n.doubleValue() : null;
        Double budget = a.get("budget") instanceof Number n ? n.doubleValue() : null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = searchClient.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v1/search/products");
                        if (keyword != null && !keyword.isBlank()) {
                            b = b.queryParam("keyword", keyword);
                        }
                        if (categoryId != null && categoryId > 0) b = b.queryParam("categoryId", categoryId);
                        if (priceMin != null) b = b.queryParam("priceMin", priceMin);
                        if (priceMax != null) b = b.queryParam("priceMax", priceMax);
                        return b.build();
                    })
                    .retrieve().body(Map.class);
            if (node != null && node.get("data") != null) {
                Object dataObj = node.get("data");
                if (dataObj instanceof Map<?, ?> rawData) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) rawData);
                    List<Map<String, Object>> filtered = filterSearchItems(data.get("items"), categoryId, priceMin, priceMax);
                    rankByBudget(filtered, budget);
                    data.put("items", filtered);
                    data.put("total", filtered.size());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("keyword", keyword);
                    out.put("source", "search-service");
                    if (categoryId != null) out.put("categoryId", categoryId);
                    if (priceMin != null) out.put("priceMin", priceMin);
                    if (priceMax != null) out.put("priceMax", priceMax);
                    if (budget != null) out.put("budget", budget);
                    out.put("data", data);
                    return out;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("keyword", keyword);
                out.put("source", "search-service");
                out.put("data", dataObj);
                return out;
            }
            throw new BusinessException("AI50310", "搜索服务未返回商品数据");
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new BusinessException("AI50310", "搜索服务失败: HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            throw new BusinessException("AI50310", "搜索服务不可用，已拒绝回落演示商品");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterSearchItems(Object itemsRaw, Long categoryId,
                                                        Double priceMin, Double priceMax) {
        if (!(itemsRaw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            if (categoryId != null) {
                Object cid = item.get("categoryId");
                if (!(cid instanceof Number n) || n.longValue() != categoryId) continue;
            }
            double price = 0;
            Object pv = item.get("minPrice");
            if (pv instanceof Number n) price = n.doubleValue();
            if (priceMin != null && price < priceMin) continue;
            if (priceMax != null && price > priceMax) continue;
            out.add(item);
        }
        return out;
    }

    private void rankByBudget(List<Map<String, Object>> items, Double budget) {
        if (items == null || items.size() < 2 || budget == null) return;
        items.sort((a, b) -> {
            double pa = itemPrice(a);
            double pb = itemPrice(b);
            // Prefer under/at budget when ranking soft results
            boolean aOver = pa > budget;
            boolean bOver = pb > budget;
            if (aOver != bOver) return aOver ? 1 : -1;
            int cmp = Double.compare(Math.abs(pa - budget), Math.abs(pb - budget));
            if (cmp != 0) return cmp;
            return Double.compare(pa, pb);
        });
    }

    private double itemPrice(Map<String, Object> item) {
        Object pv = item.get("minPrice");
        if (pv instanceof Number n) return n.doubleValue();
        return Double.MAX_VALUE;
    }

    private Map<String, Object> getProductDetail(long spuId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = productClient.get()
                    .uri("/api/v1/products/spus/{id}", spuId)
                    .retrieve().body(Map.class);
            if (node != null && node.get("data") != null) {
                return Map.of("source", "product-service", "spuId", spuId, "data", node.get("data"));
            }
            throw new BusinessException("AI50311", "商品服务未返回详情");
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new BusinessException("AI50311", "商品服务失败: HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            throw new BusinessException("AI50311", "商品服务不可用，已拒绝回落演示商品");
        }
    }

    private Map<String, Object> compareProducts(Object spuIdsRaw) {
        List<Long> ids = new ArrayList<>();
        if (spuIdsRaw instanceof List<?> list) {
            for (Object o : list) {
                long id = longVal(o, 0);
                if (id > 0) ids.add(id);
            }
        }
        if (ids.size() < 2) {
            throw new BusinessException("AI40004", "对比至少需要两个有效 spuId");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> detail = getProductDetail(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("spuId", id);
            row.put("detail", detail.getOrDefault("data", detail));
            items.add(row);
        }
        return Map.of("items", items, "diff", List.of("请以结构化商品字段对比价格、规格与销量", "参数来自商品服务，不以模型臆造为准"));
    }

    private Map<String, Object> getCart(String userToken) {
        return authGet(tradeClient, "/api/v1/trade/cart", userToken, "cart");
    }

    private Map<String, Object> getUserOrders(String userToken) {
        Map<String, Object> data = authGet(tradeClient, "/api/v1/trade/orders", userToken, "orders");
        data.put("scope", "SELF_ONLY");
        return data;
    }

    private Map<String, Object> getOrderDetail(String userToken, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException("AI40001", "缺少 orderNo");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = tradeClient.get()
                    .uri("/api/v1/trade/orders/{orderNo}", orderNo)
                    .headers(h -> applyAuth(h, userToken))
                    .retrieve().body(Map.class);
            Object data = node == null ? null : node.get("data");
            if (data == null) throw new BusinessException("AI40404", "订单不存在或无权访问");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scope", "SELF_ONLY");
            out.put("orderNo", orderNo);
            out.put("data", data);
            out.put("source", "trade-service");
            return out;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
                throw new BusinessException("AI40304", "订单对象级鉴权失败：仅可查询本人订单");
            }
            throw new BusinessException("AI50301", "订单查询失败: HTTP " + ex.getStatusCode().value());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("AI50301", "订单查询失败");
        }
    }

    private Map<String, Object> getLogistics(String userToken, Map<String, Object> a) {
        String orderNo = str(a.get("orderNo"), "");
        if (!orderNo.isBlank()) {
            Map<String, Object> order = getOrderDetail(userToken, orderNo);
            return Map.of("scope", "SELF_ONLY", "orderNo", orderNo, "source", "trade-order",
                    "packageNo", a.getOrDefault("packageNo", ""), "order", order.get("data"),
                    "message", "物流轨迹以订单详情中的履约信息为准");
        }
        return Map.of("scope", "SELF_ONLY", "packageNo", a.get("packageNo"),
                "message", "请提供本人 orderNo 以查询物流");
    }

    private Map<String, Object> addCartItem(String userToken, long skuId, int quantity) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = tradeClient.post()
                    .uri("/api/v1/trade/cart/items")
                    .headers(h -> {
                        applyAuth(h, userToken);
                        h.set("Content-Type", "application/json");
                    })
                    .body(Map.of("skuId", skuId, "quantity", Math.max(1, quantity)))
                    .retrieve().body(Map.class);
            Object data = node == null ? null : node.get("data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ADDED");
            out.put("skuId", skuId);
            out.put("quantity", quantity);
            out.put("source", "trade-service");
            out.put("cart", data == null ? Map.of() : data);
            out.put("message", "已加入购物车，可在购物车页调整数量或结算");
            return out;
        } catch (RestClientResponseException ex) {
            throw new BusinessException("AI50302", "加购失败: HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            throw new BusinessException("AI50302", "加购失败，请稍后重试");
        }
    }

    private Map<String, Object> createAftersaleDraft(long userId, Map<String, Object> a) {
        // 正式售后申请走业务页；此处仅落 AI 侧草稿，不调用 aftersale APPLIED。
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("type", a.getOrDefault("type", "REFUND_ONLY"));
        draft.put("orderNo", a.get("orderNo"));
        draft.put("orderItemId", a.get("orderItemId"));
        draft.put("reason", a.getOrDefault("reason", "AI 生成售后草稿"));
        draft.put("note", "非正式退款；正式提交前需用户在售后页确认");
        // keep aftersale client wired for future confirm-path health probe
        try {
            aftersaleClient.get().uri("/actuator/health").retrieve().toBodilessEntity();
            draft.put("aftersaleService", "UP");
        } catch (Exception ignored) {
            draft.put("aftersaleService", "UNKNOWN");
        }
        return saveDraft(userId, "AFTERSALE", a, draft);
    }

    private Map<String, Object> saveDraft(long userId, String draftType, Map<String, Object> args, Map<String, Object> payload) {
        long id = DistributedId.next();
        String no = "AID" + id;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ai_business_draft(id,draft_no,user_id,draft_type,status,payload_json,created_at,updated_at)
                VALUES(?,?,?,?, 'DRAFT',?,?,?)
                """, id, no, userId, draftType, json(payload), Timestamp.from(now), Timestamp.from(now));
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("draftNo", no);
        out.put("draftId", id);
        out.put("status", "DRAFT");
        out.put("message", out.getOrDefault("message", "已生成草稿，正式提交需用户确认"));
        out.put("args", args);
        return out;
    }

    private Map<String, Object> authGet(RestClient client, String path, String userToken, String label) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = client.get()
                    .uri(path)
                    .headers(h -> applyAuth(h, userToken))
                    .retrieve().body(Map.class);
            Object data = node == null ? null : node.get("data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "trade-service");
            out.put(label, data == null ? List.of() : data);
            return out;
        } catch (RestClientResponseException ex) {
            throw new BusinessException("AI50301", label + " 查询失败: HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            throw new BusinessException("AI50301", label + " 查询失败");
        }
    }

    private void applyAuth(org.springframework.http.HttpHeaders h, String userToken) {
        if (userToken == null || userToken.isBlank()) {
            throw new BusinessException("AI40101", "该工具需要登录");
        }
        h.set("Authorization", userToken.startsWith("Bearer ") ? userToken : "Bearer " + userToken);
    }

    private Map<String, Object> requireUser(long userId, Map<String, Object> payload) {
        if (userId <= 0) throw new BusinessException("AI40101", "该工具需要登录");
        return payload;
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private static long longVal(Object v, long def) {
        if (v == null) return def;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int intVal(Object v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
