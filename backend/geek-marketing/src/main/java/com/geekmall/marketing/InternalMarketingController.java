package com.geekmall.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/marketing")
class InternalMarketingController {
    private final JdbcTemplate jdbc;
    private final PromotionService promotions;
    private final SeckillInventoryService seckillInventory;
    private final String internalKey;

    InternalMarketingController(JdbcTemplate jdbc, PromotionService promotions, SeckillInventoryService seckillInventory,
                              @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.promotions = promotions;
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
        var rows = jdbc.queryForList("SELECT id,status,trade_order_no FROM marketing_seckill_order WHERE " + column + "=? FOR UPDATE", value);
        if (rows.isEmpty()) throw new BusinessException("MK40905", "秒杀请求不存在或已失效");
        var row = rows.getFirst();
        if ("CREATED".equals(row.get("status")) && tradeOrderNo.equals(row.get("trade_order_no"))) return;
        if (!"QUEUED".equals(row.get("status"))) throw new BusinessException("MK40905", "秒杀请求状态与交易订单冲突");
        int confirmed = jdbc.update("""
                UPDATE marketing_seckill_order SET status='CREATED',trade_order_no=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND expires_at>CURRENT_TIMESTAMP(3)
                """, tradeOrderNo, row.get("id"));
        if (confirmed == 0) {
            throw new BusinessException("MK40905", "秒杀请求已过期");
        }
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

    @PostMapping("/seckill/{skuId}/orders")
    ApiResponse<Map<String, Object>> internalSeckillOrder(@RequestHeader("X-Internal-Key") String key,
                                                          @PathVariable long skuId,
                                                          @RequestBody JsonNode body) {
        require(key);
        var sessionId = body.path("sessionId");
        var userId = body.path("userId");
        var quantity = body.path("quantity");
        if (!validLong(sessionId) || !validLong(userId) || !quantity.isIntegralNumber() || !quantity.canConvertToInt()) {
            throw new BusinessException("MK40031", "秒杀下单参数无效");
        }
        var address = body.path("addressId");
        Long addressId = validLong(address) ? address.longValue() : null;
        var result = seckillInventory.order(sessionId.longValue(), skuId, userId.longValue(), quantity.intValue(),
                addressId, body.path("requestId").textValue());
        return ApiResponse.success(result.response(false), null);
    }

    private static boolean validLong(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong();
    }

    private void require(String key) {
        if (!internalKey.equals(key)) throw new BusinessException("AU40304", "内部服务凭证无效");
    }
}
