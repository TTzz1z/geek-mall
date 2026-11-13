package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
class PromotionService {
    private final JdbcTemplate jdbc;

    PromotionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> quote(long userId, BigDecimal merchandiseTotal, Long userCouponId) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal discount = BigDecimal.ZERO;

        for (var row : jdbc.queryForList(
                "SELECT id,campaign_type,name,rule_json FROM marketing_campaign WHERE status='ENABLED' AND campaign_type='FULL_REDUCTION'")) {
            var rule = parseRule(String.valueOf(row.get("rule_json")));
            BigDecimal threshold = new BigDecimal(String.valueOf(rule.getOrDefault("threshold", "0")));
            BigDecimal amount = new BigDecimal(String.valueOf(rule.getOrDefault("discount", "0")));
            if (merchandiseTotal.compareTo(threshold) >= 0 && amount.signum() > 0) {
                lines.add(line("FULL_REDUCTION", String.valueOf(row.get("id")), String.valueOf(row.get("name")), amount));
                discount = discount.add(amount);
            }
        }

        if (userCouponId != null) {
            var coupon = jdbc.queryForList(
                    "SELECT uc.id,uc.template_id,t.name,t.threshold_amount,t.discount_amount FROM marketing_user_coupon uc JOIN marketing_coupon_template t ON t.id=uc.template_id WHERE uc.id=? AND uc.user_id=? AND uc.status='UNUSED' AND t.status='ENABLED' AND t.start_at<=CURRENT_TIMESTAMP AND t.end_at>CURRENT_TIMESTAMP",
                    userCouponId, userId);
            if (coupon.isEmpty()) throw new BusinessException("MK40905", "优惠券不可用");
            var c = coupon.getFirst();
            BigDecimal threshold = (BigDecimal) c.get("threshold_amount");
            BigDecimal amount = (BigDecimal) c.get("discount_amount");
            if (merchandiseTotal.compareTo(threshold) < 0) throw new BusinessException("MK40906", "未达到优惠券门槛");
            lines.add(line("COUPON", String.valueOf(c.get("id")), String.valueOf(c.get("name")), amount));
            discount = discount.add(amount);
        }

        if (discount.compareTo(merchandiseTotal) > 0) discount = merchandiseTotal;
        return Map.of("discountAmount", discount, "promotionLines", lines);
    }

    @Transactional
    Map<String, Object> freezeCoupon(String orderNo, long userId, long userCouponId, BigDecimal amount) {
        var coupons = jdbc.queryForList("""
                SELECT id FROM marketing_user_coupon WHERE id=? AND user_id=? FOR UPDATE
                """, userCouponId, userId);
        if (coupons.isEmpty()) throw new BusinessException("MK40907", "优惠券冻结失败");
        var old = jdbc.queryForList("""
                SELECT status,user_coupon_id,amount FROM marketing_coupon_reservation WHERE order_no=? FOR UPDATE
                """, orderNo);
        if (!old.isEmpty()) {
            var reservation = old.getFirst();
            if (((Number) reservation.get("user_coupon_id")).longValue() != userCouponId
                    || ((BigDecimal) reservation.get("amount")).compareTo(amount) != 0
                    || !"FROZEN".equals(reservation.get("status"))) {
                throw new BusinessException("MK40907", "优惠券冻结请求与已有预留不一致");
            }
            return Map.of("orderNo", orderNo, "idempotentReplay", true);
        }
        int changed = jdbc.update(
                "UPDATE marketing_user_coupon SET status='FROZEN',freeze_order_no=?,frozen_amount=? WHERE id=? AND user_id=? AND status='UNUSED'",
                orderNo, amount, userCouponId, userId);
        if (changed == 0) throw new BusinessException("MK40907", "优惠券冻结失败");
        long templateId = jdbc.queryForObject("SELECT template_id FROM marketing_user_coupon WHERE id=?", Long.class, userCouponId);
        jdbc.update("INSERT INTO marketing_coupon_reservation(id,order_no,user_coupon_id,template_id,amount,status,created_at,updated_at) VALUES(?,?,?,?,?,'FROZEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                DistributedId.next(), orderNo, userCouponId, templateId, amount);
        return Map.of("orderNo", orderNo, "idempotentReplay", false);
    }

    @Transactional
    Map<String, Object> settleCoupon(String orderNo, String action) {
        var rows = jdbc.queryForList("SELECT user_coupon_id,amount,status FROM marketing_coupon_reservation WHERE order_no=? FOR UPDATE", orderNo);
        if (rows.isEmpty()) {
            if ("release".equals(action)) return Map.of("orderNo", orderNo, "status", "RELEASED", "idempotentReplay", true);
            throw new BusinessException("MK40402", "优惠券冻结记录不存在");
        }
        var row = rows.getFirst();
        String target = "use".equals(action) ? "USED" : "RELEASED";
        if (target.equals(row.get("status"))) return Map.of("orderNo", orderNo, "status", target, "idempotentReplay", true);
        if (!"FROZEN".equals(row.get("status"))) throw new BusinessException("MK40908", "优惠券状态不可转换");
        long userCouponId = ((Number) row.get("user_coupon_id")).longValue();
        if ("USED".equals(target)) {
            jdbc.update("UPDATE marketing_user_coupon SET status='USED',used_order_no=?,used_at=CURRENT_TIMESTAMP WHERE id=?", orderNo, userCouponId);
        } else {
            jdbc.update("UPDATE marketing_user_coupon SET status='UNUSED',freeze_order_no=NULL,frozen_amount=0 WHERE id=?", userCouponId);
        }
        jdbc.update("UPDATE marketing_coupon_reservation SET status=?,updated_at=CURRENT_TIMESTAMP WHERE order_no=?", target, orderNo);
        return Map.of("orderNo", orderNo, "status", target, "idempotentReplay", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRule(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> line(String type, String sourceId, String label, BigDecimal amount) {
        return Map.of("type", type, "sourceId", sourceId, "label", label, "amount", amount.setScale(2, RoundingMode.HALF_UP));
    }
}
