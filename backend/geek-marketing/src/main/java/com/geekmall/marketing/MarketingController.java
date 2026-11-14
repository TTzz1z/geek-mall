package com.geekmall.marketing;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class MarketingController {
    private final JdbcTemplate jdbc;
    private final RedisLuaClient redis;
    private final MarketingEventStore events;
    private final SeckillInventoryService seckill;
    private final PromotionService promotions;

    MarketingController(JdbcTemplate jdbc, RedisLuaClient redis, MarketingEventStore events,
                        SeckillInventoryService seckill, PromotionService promotions) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.events = events;
        this.seckill = seckill;
        this.promotions = promotions;
    }

    @GetMapping("/marketing/coupons/available")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> coupons(HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT id,name,threshold_amount AS thresholdAmount,discount_amount AS discountAmount,end_at AS endAt FROM marketing_coupon_template WHERE status='ENABLED' AND start_at<=CURRENT_TIMESTAMP AND end_at>CURRENT_TIMESTAMP AND received_qty<total_qty"), r);
    }

    @GetMapping("/marketing/coupons/mine")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> myCoupons(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT uc.id,t.name,t.threshold_amount AS thresholdAmount,t.discount_amount AS discountAmount,uc.status,uc.received_at AS receivedAt FROM marketing_user_coupon uc JOIN marketing_coupon_template t ON t.id=uc.template_id WHERE uc.user_id=? ORDER BY uc.received_at DESC",
                p.userId()), r);
    }

    @PostMapping("/marketing/coupons/{id}/receive")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> receive(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        int changed = jdbc.update(
                "UPDATE marketing_coupon_template SET received_qty=received_qty+1 WHERE id=? AND status='ENABLED' AND start_at<=CURRENT_TIMESTAMP AND end_at>CURRENT_TIMESTAMP AND received_qty<total_qty",
                id);
        if (changed == 0) throw new BusinessException("MK40901", "优惠券已领完或不可领取");
        try {
            jdbc.update("INSERT INTO marketing_user_coupon(id,template_id,user_id,status,received_at) VALUES(?,?,?,'UNUSED',CURRENT_TIMESTAMP)",
                    DistributedId.next(), id, p.userId());
        } catch (Exception e) {
            throw new BusinessException("MK40902", "同一优惠券只能领取一次");
        }
        return ok(Map.of("templateId", id, "status", "UNUSED"), r);
    }

    @GetMapping("/marketing/seckill/sessions")
    ApiResponse<List<Map<String, Object>>> sessions(HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT s.id,s.session_code AS sessionCode,s.start_at AS startAt,s.end_at AS endAt,i.sku_id AS skuId,i.price,i.stock_qty AS stockQty,i.limit_per_user AS limitPerUser,i.warm_status AS warmStatus FROM marketing_seckill_session s JOIN marketing_seckill_item i ON i.session_id=s.id WHERE s.status='ENABLED' AND i.status='ENABLED' ORDER BY s.start_at"), r);
    }

    @PostMapping("/marketing/seckill/{skuId}/qualify")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<Map<String, Object>> qualify(@PathVariable long skuId, @RequestParam long sessionId,
                                             @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        var rows = jdbc.queryForList("""
                SELECT i.stock_qty AS stockQty,i.status AS itemStatus,s.start_at AS startAt,s.end_at AS endAt,s.status AS sessionStatus
                FROM marketing_seckill_item i
                JOIN marketing_seckill_session s ON s.id=i.session_id
                WHERE i.session_id=? AND i.sku_id=?
                """, sessionId, skuId);
        if (rows.isEmpty()) {
            return ok(Map.of("qualified", false, "sessionId", sessionId, "skuId", skuId, "userId", p.userId(),
                    "reason", "NOT_FOUND"), r);
        }
        var row = rows.getFirst();
        Instant now = Instant.now();
        Instant start = ((Timestamp) row.get("startAt")).toInstant();
        Instant end = ((Timestamp) row.get("endAt")).toInstant();
        boolean inWindow = !now.isBefore(start) && now.isBefore(end) && "ENABLED".equals(String.valueOf(row.get("sessionStatus")));
        int stock = ((Number) row.get("stockQty")).intValue();
        boolean itemEnabled = "ENABLED".equals(String.valueOf(row.get("itemStatus")));
        boolean qualified = inWindow && stock > 0 && itemEnabled;
        String reason = qualified ? "OK"
                : (!inWindow ? "SESSION_NOT_ACTIVE" : (!itemEnabled ? "ITEM_DISABLED" : "OUT_OF_STOCK"));
        return ok(Map.of(
                "qualified", qualified,
                "sessionId", sessionId,
                "skuId", skuId,
                "userId", p.userId(),
                "reason", reason
        ), r);
    }

    @PostMapping("/marketing/seckill/{skuId}/orders")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> order(@PathVariable long skuId, @Valid @RequestBody SeckillOrder body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
                                           @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        // Idempotency BEFORE Redis Lua — avoids double-decr on client retry with same key.
        var existing = jdbc.queryForList(
                "SELECT id,status,trade_order_no AS tradeOrderNo FROM marketing_seckill_order WHERE request_id=?", requestId);
        if (!existing.isEmpty()) {
            var e = existing.getFirst();
            return ok(Map.of("seckillOrderId", e.get("id"), "status", e.get("status"),
                    "tradeOrderNo", e.get("tradeOrderNo"), "idempotentReplay", true), r);
        }
        var row = item(body.sessionId(), skuId);
        seckill.warm(body.sessionId(), skuId);
        String stock = "seckill:stock:" + body.sessionId() + ":" + skuId;
        String limit = "seckill:user:" + body.sessionId() + ":" + skuId + ":" + p.userId();
        String lua = "local n=redis.call('GET',KEYS[1]); if not n then return -3 end; if tonumber(n)<tonumber(ARGV[1]) then return -1 end; local u=redis.call('GET',KEYS[2]); if u and tonumber(u)+tonumber(ARGV[1])>tonumber(ARGV[2]) then return -2 end; redis.call('DECRBY',KEYS[1],ARGV[1]); redis.call('INCRBY',KEYS[2],ARGV[1]); redis.call('EXPIRE',KEYS[2],ARGV[3]); return 1";
        Object result = redis.eval(lua, List.of(stock, limit),
                List.of(String.valueOf(body.quantity()), String.valueOf(row.get("limit_per_user")), "3600"));
        if (!Long.valueOf(1).equals(result)) {
            throw new BusinessException("MK40903", switch (((Number) result).intValue()) {
                case -1 -> "秒杀库存不足";
                case -2 -> "超过单人限购";
                default -> "秒杀尚未预热";
            });
        }
        long id = DistributedId.next();
        Instant expires = Instant.now().plusSeconds(900);
        try {
            jdbc.update(
                    "INSERT INTO marketing_seckill_order(id,session_id,sku_id,user_id,quantity,request_id,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, body.sessionId(), skuId, p.userId(), body.quantity(), requestId, Timestamp.from(expires));
        } catch (Exception e) {
            seckill.undoUncommittedHold(body.sessionId(), skuId, p.userId(), body.quantity());
            throw new BusinessException("MK40904", "秒杀请求重复");
        }
        events.append("SECKILL_ORDER", String.valueOf(id), MarketingRabbitConfiguration.SECKILL_ROUTING, Map.of(
                "seckillOrderId", id,
                "sessionId", body.sessionId(),
                "skuId", skuId,
                "userId", p.userId(),
                "quantity", body.quantity(),
                "requestId", requestId,
                "price", row.get("price"),
                "addressId", body.addressId(),
                "expiresAt", expires.toString()));
        return ok(Map.of("seckillOrderId", id, "status", "QUEUED", "requestId", requestId), r);
    }

    @PostMapping("/admin/marketing/campaigns")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<Map<String, Object>> campaign(@Valid @RequestBody Campaign b, HttpServletRequest r) {
        long id = DistributedId.next();
        jdbc.update("INSERT INTO marketing_campaign(id,campaign_type,name,rule_json,status,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                id, b.type(), b.name(), b.ruleJson(), "ENABLED");
        return ok(Map.of("campaignId", id), r);
    }

    @GetMapping("/admin/marketing/seckill/sessions")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> adminSessions(HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT s.id AS sessionId, s.session_code AS sessionCode, s.start_at AS startAt, s.end_at AS endAt, s.status AS sessionStatus,
                       i.sku_id AS skuId, i.price, i.stock_qty AS stockQty, i.limit_per_user AS limitPerUser, i.warm_status AS warmStatus, i.status AS itemStatus
                FROM marketing_seckill_session s
                LEFT JOIN marketing_seckill_item i ON i.session_id=s.id
                ORDER BY s.start_at DESC, i.sku_id
                """), r);
    }

    @PostMapping("/admin/marketing/seckill/warmup")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> warmup(@RequestParam long sessionId, @RequestParam long skuId, HttpServletRequest r) {
        seckill.warm(sessionId, skuId);
        return ok(Map.of("sessionId", sessionId, "skuId", skuId, "warmStatus", "HOT"), r);
    }

    private Map<String, Object> item(long s, long sku) {
        var rows = jdbc.queryForList(
                "SELECT i.price,i.limit_per_user,s.start_at,s.end_at FROM marketing_seckill_item i JOIN marketing_seckill_session s ON s.id=i.session_id WHERE i.session_id=? AND i.sku_id=? AND i.status='ENABLED' AND s.status='ENABLED' AND s.start_at<=CURRENT_TIMESTAMP AND s.end_at>CURRENT_TIMESTAMP",
                s, sku);
        if (rows.isEmpty()) throw new BusinessException("MK40401", "秒杀商品或场次不可用");
        return rows.getFirst();
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record SeckillOrder(@NotNull Long sessionId, @NotNull Long addressId, @Min(1) @Max(10) int quantity) {}

    record Campaign(@NotBlank String type, @NotBlank String name, @NotBlank String ruleJson) {}
}
