package com.geekmall.marketing;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/marketing")
class InternalMarketingController {
    private final JdbcTemplate jdbc;
    private final PromotionService promotions;
    private final RedisLuaClient redis;
    private final MarketingEventStore events;
    private final SeckillInventoryService seckillInventory;
    private final String internalKey;

    InternalMarketingController(JdbcTemplate jdbc, PromotionService promotions, RedisLuaClient redis,
                              MarketingEventStore events, SeckillInventoryService seckillInventory,
                              @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.promotions = promotions;
        this.redis = redis;
        this.events = events;
        this.seckillInventory = seckillInventory;
        this.internalKey = internalKey;
    }

    @PostMapping("/promotions/quote")
    ApiResponse<Map<String, Object>> quote(@RequestHeader("X-Internal-Key") String key,
                                           @RequestParam long userId,
                                           @RequestParam BigDecimal merchandiseTotal,
                                           @RequestParam(required = false) Long userCouponId) {
        require(key);
        return ApiResponse.success(promotions.quote(userId, merchandiseTotal, userCouponId), null);
    }

    @PostMapping("/coupons/freeze")
    ApiResponse<Map<String, Object>> freezeCoupon(@RequestHeader("X-Internal-Key") String key,
                                                  @RequestParam String orderNo,
                                                  @RequestParam long userId,
                                                  @RequestParam long userCouponId,
                                                  @RequestParam BigDecimal amount) {
        require(key);
        return ApiResponse.success(promotions.freezeCoupon(orderNo, userId, userCouponId, amount), null);
    }

    @PostMapping("/coupons/{orderNo}/{action:use|release}")
    ApiResponse<Map<String, Object>> settleCoupon(@RequestHeader("X-Internal-Key") String key,
                                                   @PathVariable String orderNo,
                                                   @PathVariable String action) {
        require(key);
        return ApiResponse.success(promotions.settleCoupon(orderNo, action), null);
    }

    @PostMapping("/seckill/{seckillOrderId}/created")
    @Transactional
    ApiResponse<Map<String, Object>> markCreated(@RequestHeader("X-Internal-Key") String key,
                                                 @PathVariable long seckillOrderId,
                                                 @RequestParam String tradeOrderNo) {
        require(key);
        confirmSeckill("id", seckillOrderId, tradeOrderNo);
        return ApiResponse.success(Map.of("seckillOrderId", seckillOrderId, "tradeOrderNo", tradeOrderNo), null);
    }

    @PostMapping("/seckill/requests/{requestId}/created")
    @Transactional
    ApiResponse<Map<String, Object>> markCreatedByRequest(@RequestHeader("X-Internal-Key") String key,
                                                          @PathVariable String requestId,
                                                          @RequestParam String tradeOrderNo) {
        require(key);
        confirmSeckill("request_id", requestId, tradeOrderNo);
        return ApiResponse.success(Map.of("requestId", requestId, "tradeOrderNo", tradeOrderNo), null);
    }

    private void confirmSeckill(String column, Object value, String tradeOrderNo) {
        var rows = jdbc.queryForList("SELECT id,status,trade_order_no,CASE WHEN expires_at<=CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS expired FROM marketing_seckill_order WHERE " + column + "=? FOR UPDATE", value);
        if (rows.isEmpty()) throw new BusinessException("MK40905", "秒杀请求不存在或已失效");
        var row = rows.getFirst();
        if ("CREATED".equals(row.get("status")) && tradeOrderNo.equals(row.get("trade_order_no"))) return;
        if (!"QUEUED".equals(row.get("status"))) throw new BusinessException("MK40905", "秒杀请求状态与交易订单冲突");
        if (((Number) row.get("expired")).intValue() == 1) {
            throw new BusinessException("MK40905", "秒杀请求已过期");
        }
        jdbc.update("UPDATE marketing_seckill_order SET status='CREATED',trade_order_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", tradeOrderNo, row.get("id"));
    }

    @PostMapping("/seckill/release")
    ApiResponse<Map<String, Object>> releaseSeckill(@RequestHeader("X-Internal-Key") String key,
                                                     @RequestBody Map<String, Object> body) {
        require(key);
        long sessionId = ((Number) body.get("sessionId")).longValue();
        long skuId = ((Number) body.get("skuId")).longValue();
        long userId = ((Number) body.get("userId")).longValue();
        int quantity = ((Number) body.get("quantity")).intValue();
        seckillInventory.release(sessionId, skuId, userId, quantity);
        return ApiResponse.success(Map.of("released", true), null);
    }

    @PostMapping("/seckill/warmup")
    ApiResponse<Map<String, Object>> warmup(@RequestHeader("X-Internal-Key") String key,
                                            @RequestParam long sessionId,
                                            @RequestParam long skuId) {
        require(key);
        seckillInventory.warm(sessionId, skuId);
        return ApiResponse.success(Map.of("sessionId", sessionId, "skuId", skuId, "warmStatus", "HOT"), null);
    }

    void warm(long sessionId, long skuId) {
        seckillInventory.warm(sessionId, skuId);
    }

    @PostMapping("/seckill/{skuId}/orders")
    @Transactional
    ApiResponse<Map<String, Object>> internalSeckillOrder(@RequestHeader("X-Internal-Key") String key,
                                                          @PathVariable long skuId,
                                                          @RequestBody Map<String, Object> body) {
        require(key);
        long sessionId = ((Number) body.get("sessionId")).longValue();
        long userId = ((Number) body.get("userId")).longValue();
        int quantity = ((Number) body.get("quantity")).intValue();
        String requestId = String.valueOf(body.get("requestId"));
        // Idempotency BEFORE Redis Lua — same key must not double-decr stock on retry.
        var existing = jdbc.queryForList("SELECT id,status,trade_order_no FROM marketing_seckill_order WHERE request_id=?", requestId);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            return ApiResponse.success(Map.of("seckillOrderId", row.get("id"), "status", row.get("status"), "tradeOrderNo", row.get("trade_order_no"), "idempotentReplay", true), null);
        }
        var item = item(sessionId, skuId);
        warmIfCold(sessionId, skuId);
        String stock = "seckill:stock:" + sessionId + ":" + skuId;
        String limit = "seckill:user:" + sessionId + ":" + skuId + ":" + userId;
        String lua = "local n=redis.call('GET',KEYS[1]); if not n then return -3 end; if tonumber(n)<tonumber(ARGV[1]) then return -1 end; local u=redis.call('GET',KEYS[2]); if u and tonumber(u)+tonumber(ARGV[1])>tonumber(ARGV[2]) then return -2 end; redis.call('DECRBY',KEYS[1],ARGV[1]); redis.call('INCRBY',KEYS[2],ARGV[1]); redis.call('EXPIRE',KEYS[2],ARGV[3]); return 1";
        Object result = redis.eval(lua, List.of(stock, limit), List.of(String.valueOf(quantity), String.valueOf(item.get("limit_per_user")), "3600"));
        if (!Long.valueOf(1).equals(result)) {
            throw new BusinessException("MK40903", switch (((Number) result).intValue()) {
                case -1 -> "秒杀库存不足";
                case -2 -> "超过单人限购";
                default -> "秒杀尚未预热";
            });
        }
        long id = DistributedId.next();
        Instant expires = Instant.now().plusSeconds(900);
        Object addressIdRaw = body.get("addressId");
        if (addressIdRaw == null) {
            throw new BusinessException("MK40030", "秒杀下单必须选择收货地址");
        }
        long addressId = ((Number) addressIdRaw).longValue();
        try {
            jdbc.update("INSERT INTO marketing_seckill_order(id,session_id,sku_id,user_id,quantity,request_id,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, sessionId, skuId, userId, quantity, requestId, java.sql.Timestamp.from(expires));
        } catch (Exception e) {
            seckillInventory.undoUncommittedHold(sessionId, skuId, userId, quantity);
            throw new BusinessException("MK40904", "秒杀请求重复");
        }
        events.append("SECKILL_ORDER", String.valueOf(id), MarketingRabbitConfiguration.SECKILL_ROUTING,
                Map.of("seckillOrderId", id, "sessionId", sessionId, "skuId", skuId, "userId", userId, "quantity", quantity,
                        "requestId", requestId, "price", item.get("price"), "addressId", addressId, "expiresAt", expires.toString()));
        return ApiResponse.success(Map.of("seckillOrderId", id, "status", "QUEUED", "idempotentReplay", false), null);
    }

    private void warmIfCold(long sessionId, long skuId) {
        String status = jdbc.queryForObject("SELECT warm_status FROM marketing_seckill_item WHERE session_id=? AND sku_id=?", String.class, sessionId, skuId);
        if (!"HOT".equals(status)) warm(sessionId, skuId);
    }

    private Map<String, Object> item(long sessionId, long skuId) {
        var rows = jdbc.queryForList(
                "SELECT i.price,i.limit_per_user,i.stock_qty,s.start_at,s.end_at FROM marketing_seckill_item i JOIN marketing_seckill_session s ON s.id=i.session_id WHERE i.session_id=? AND i.sku_id=? AND i.status='ENABLED' AND s.status='ENABLED' AND s.start_at<=CURRENT_TIMESTAMP AND s.end_at>CURRENT_TIMESTAMP",
                sessionId, skuId);
        if (rows.isEmpty()) throw new BusinessException("MK40401", "秒杀商品或场次不可用");
        return rows.getFirst();
    }

    private void require(String key) {
        if (!internalKey.equals(key)) throw new BusinessException("AU40304", "内部服务凭证无效");
    }
}
