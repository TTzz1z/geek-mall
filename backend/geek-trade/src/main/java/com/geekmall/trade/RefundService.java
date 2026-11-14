package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
class RefundService {
    private final JdbcTemplate jdbc;
    private final SettlementClient settlement;
    private final TradeTasks tasks;

    RefundService(JdbcTemplate jdbc, SettlementClient settlement, TradeTasks tasks) {
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.tasks = tasks;
    }

    @Transactional
    Map<String, Object> refund(String orderNo, long itemId, String aftersaleId, int qty, BigDecimal approvedAmount) {
        var items = jdbc.queryForList("""
                SELECT i.id,i.order_id,i.quantity,i.aftersale_qty,i.payable_amount,o.status,o.paid_payment_id
                FROM trade_order_item i JOIN trade_order o ON o.id=i.order_id
                WHERE i.id=? AND o.order_no=? FOR UPDATE
                """, itemId, orderNo);
        if (items.isEmpty()) throw new BusinessException("TR40420", "订单项不存在");
        Map<String, Object> item = items.getFirst();
        var old = jdbc.queryForList("""
                SELECT refund_no AS refundNo,amount,status,order_item_id,quantity
                FROM trade_refund WHERE aftersale_id=? FOR UPDATE
                """, aftersaleId);
        if (!old.isEmpty()) {
            var x = old.getFirst();
            if (((Number) x.remove("order_item_id")).longValue() != itemId
                    || ((Number) x.remove("quantity")).intValue() != qty) {
                throw new BusinessException("TR40923", "售后单已绑定其他退款请求");
            }
            x.put("idempotentReplay", true);
            return x;
        }
        if (!Set.of("PAID", "PARTIAL_SHIPPED", "SHIPPED", "COMPLETED").contains(item.get("status")) || item.get("paid_payment_id") == null) {
            throw new BusinessException("TR40920", "订单尚未支付，不能退款");
        }
        int total = ((Number) item.get("quantity")).intValue();
        int already = ((Number) item.get("aftersale_qty")).intValue();
        if (qty <= 0 || qty > total || already + qty > total) {
            throw new BusinessException("TR40020", "退款商品数量无效或超过可退数量");
        }
        BigDecimal payable = (BigDecimal) item.get("payable_amount");
        if (approvedAmount != null && (approvedAmount.signum() < 0 || approvedAmount.stripTrailingZeros().scale() > 2)) {
            throw new BusinessException("TR40020", "审核退款金额无效");
        }
        BigDecimal proportional = payable.multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(total), 2, RoundingMode.DOWN);
        BigDecimal requested = approvedAmount != null && approvedAmount.signum() > 0 ? approvedAmount : proportional;
        if (requested.compareTo(payable) > 0) {
            throw new BusinessException("TR40922", "审核退款金额超过订单项可退余额");
        }
        BigDecimal used = Objects.requireNonNullElse(
                jdbc.queryForObject("SELECT SUM(amount) FROM trade_refund WHERE order_item_id=? AND status IN ('PROCESSING','SUCCESS')", BigDecimal.class, itemId),
                BigDecimal.ZERO);
        if (used.add(requested).compareTo(payable) > 0) {
            throw new BusinessException("TR40921", "部分退款金额超过订单项可退余额");
        }
        long id = DistributedId.next();
        String no = "RF" + id;
        jdbc.update("""
                INSERT INTO trade_refund
                  (id,refund_no,payment_id,order_id,order_item_id,aftersale_id,quantity,amount,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,'PROCESSING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, no, item.get("paid_payment_id"), item.get("order_id"), itemId, aftersaleId, qty, requested);
        jdbc.update("UPDATE trade_order_item SET aftersale_qty=aftersale_qty+? WHERE id=?", qty, itemId);
        tasks.enqueue("REFUND", aftersaleId);
        return Map.of("refundNo", no, "amount", requested, "status", "PROCESSING", "idempotentReplay", false);
    }

    boolean settle(String aftersaleId) {
        var rows = jdbc.queryForList("""
                SELECT id,refund_no,order_item_id,amount,status FROM trade_refund WHERE aftersale_id=?
                """, aftersaleId);
        if (rows.isEmpty()) return false;
        var refund = rows.getFirst();
        if ("SUCCESS".equals(refund.get("status"))) return true;
        BigDecimal amount = (BigDecimal) refund.get("amount");
        if (amount.signum() > 0) {
            settlement.refund(String.valueOf(refund.get("refund_no")), ((Number) refund.get("order_item_id")).longValue(), amount);
        }
        jdbc.update("""
                UPDATE trade_refund SET status='SUCCESS',channel_refund_no=?,succeeded_at=CURRENT_TIMESTAMP,
                    updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'
                """, "MOCK-" + refund.get("refund_no"), refund.get("id"));
        return true;
    }
}
