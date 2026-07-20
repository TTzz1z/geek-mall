package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

@Service
class RefundService {
    private final JdbcTemplate jdbc;
    private final SettlementClient settlement;
    private final ObjectMapper mapper;

    RefundService(JdbcTemplate jdbc, SettlementClient settlement, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.mapper = mapper;
    }

    @Transactional
    Map<String, Object> refund(String orderNo, long itemId, String aftersaleId, int qty, BigDecimal approvedAmount) {
        var old = jdbc.queryForList("SELECT refund_no AS refundNo,amount,status FROM trade_refund WHERE aftersale_id=?", aftersaleId);
        if (!old.isEmpty()) {
            var x = old.getFirst();
            x.put("idempotentReplay", true);
            return x;
        }
        Map<String, Object> item = jdbc.queryForMap(
                "SELECT i.id,i.order_id,i.quantity,i.payable_amount,o.status,o.paid_payment_id FROM trade_order_item i JOIN trade_order o ON o.id=i.order_id WHERE i.id=? AND o.order_no=? FOR UPDATE",
                itemId, orderNo);
        if (!"PAID".equals(item.get("status")) || item.get("paid_payment_id") == null) {
            throw new BusinessException("TR40920", "订单尚未支付，不能退款");
        }
        int total = ((Number) item.get("quantity")).intValue();
        int already = ((Number) Objects.requireNonNullElse(
                jdbc.queryForObject("SELECT aftersale_qty FROM trade_order_item WHERE id=?", Integer.class, itemId), 0)).intValue();
        if (qty <= 0 || qty > total || already + qty > total) {
            throw new BusinessException("TR40020", "退款商品数量无效或超过可退数量");
        }
        BigDecimal payable = (BigDecimal) item.get("payable_amount");
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
        try {
            jdbc.update(
                    "INSERT INTO trade_refund(id,refund_no,payment_id,order_id,order_item_id,aftersale_id,quantity,amount,status,channel_refund_no,created_at,updated_at,succeeded_at) VALUES(?,?,?,?,?,?,?,?,'SUCCESS',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, no, item.get("paid_payment_id"), item.get("order_id"), itemId, aftersaleId, qty, requested, "MOCK-RF" + id);
        } catch (Exception e) {
            String payload;
            try {
                payload = mapper.writeValueAsString(Map.of("orderNo", orderNo, "orderItemId", itemId, "quantity", qty,
                        "approvedAmount", requested));
            } catch (Exception ex) {
                payload = "{}";
            }
            jdbc.update(
                    "INSERT INTO trade_compensation_task(id,task_type,business_no,payload_json,status,next_retry_at,last_error,created_at,updated_at) VALUES(?,?,?,?,'PENDING',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    DistributedId.next(), "REFUND", aftersaleId, payload, e.getMessage());
            throw new BusinessException("TR50301", "退款受理失败，已进入补偿队列");
        }
        jdbc.update("UPDATE trade_order_item SET aftersale_qty=aftersale_qty+? WHERE id=?", qty, itemId);
        try {
            settlement.refund(itemId, requested);
        } catch (Exception ignored) {
        }
        return Map.of("refundNo", no, "amount", requested, "status", "SUCCESS", "idempotentReplay", false);
    }
}
