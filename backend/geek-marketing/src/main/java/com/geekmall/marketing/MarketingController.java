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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class MarketingController {
    private final JdbcTemplate jdbc;
    private final SeckillInventoryService seckill;

    MarketingController(JdbcTemplate jdbc, SeckillInventoryService seckill) {
        this.jdbc = jdbc;
        this.seckill = seckill;
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
                "SELECT s.id,s.session_code AS sessionCode,s.start_at AS startAt,s.end_at AS endAt,i.sku_id AS skuId,i.price,GREATEST(i.stock_qty-i.reserved_qty,0) AS stockQty,i.limit_per_user AS limitPerUser,i.warm_status AS warmStatus FROM marketing_seckill_session s JOIN marketing_seckill_item i ON i.session_id=s.id WHERE s.status='ENABLED' AND i.status='ENABLED' ORDER BY s.start_at"), r);
    }

    @PostMapping("/marketing/seckill/{skuId}/qualify")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<Map<String, Object>> qualify(@PathVariable long skuId, @RequestParam long sessionId,
                                             @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        var rows = jdbc.queryForList("""
                SELECT GREATEST(i.stock_qty-i.reserved_qty,0) AS stockQty,i.status AS itemStatus,
                       CASE WHEN s.status='ENABLED' AND s.start_at<=CURRENT_TIMESTAMP(3) AND s.end_at>CURRENT_TIMESTAMP(3)
                            THEN 1 ELSE 0 END AS activeSession
                FROM marketing_seckill_item i
                JOIN marketing_seckill_session s ON s.id=i.session_id
                WHERE i.session_id=? AND i.sku_id=?
                """, sessionId, skuId);
        if (rows.isEmpty()) {
            return ok(Map.of("qualified", false, "sessionId", sessionId, "skuId", skuId, "userId", p.userId(),
                    "reason", "NOT_FOUND"), r);
        }
        var row = rows.getFirst();
        boolean inWindow = ((Number) row.get("activeSession")).intValue() == 1;
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
    ApiResponse<Map<String, Object>> order(@PathVariable long skuId, @Valid @RequestBody SeckillOrder body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
                                           @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return ok(seckill.order(body.sessionId(), skuId, p.userId(), body.quantity(), body.addressId(), requestId)
                .response(true), r);
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
                       i.sku_id AS skuId, i.price, GREATEST(i.stock_qty-i.reserved_qty,0) AS stockQty,
                       i.limit_per_user AS limitPerUser, i.warm_status AS warmStatus, i.status AS itemStatus
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

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record SeckillOrder(@NotNull Long sessionId, @NotNull Long addressId, @Min(1) @Max(10) int quantity) {}

    record Campaign(@NotBlank String type, @NotBlank String name, @NotBlank String ruleJson) {}
}
