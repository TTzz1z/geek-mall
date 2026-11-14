package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
class TradeRecoveryJobs {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final InventoryClient inventory;
    private final RefundService refunds;
    private final MarketingClient marketing;
    private final MemberClient member;
    private final PaymentService payments;
    private final SettlementClient settlement;
    private final SeckillOrderService seckillOrders;
    private final ObjectMapper mapper;
    private final TradeTasks tasks;

    TradeRecoveryJobs(JdbcTemplate jdbc, OrderService orders, InventoryClient inventory, RefundService refunds,
                      MarketingClient marketing, MemberClient member, PaymentService payments,
                      SettlementClient settlement, SeckillOrderService seckillOrders, ObjectMapper mapper, TradeTasks tasks) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.inventory = inventory;
        this.refunds = refunds;
        this.marketing = marketing;
        this.member = member;
        this.payments = payments;
        this.settlement = settlement;
        this.seckillOrders = seckillOrders;
        this.mapper = mapper;
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${geek.timeout.interval:5000}")
    void closeExpired() {
        for (String no : jdbc.queryForList(
                "SELECT o.order_no FROM trade_order_timeout t JOIN trade_order o ON o.id=t.order_id WHERE t.status='PENDING' AND t.execute_at<=CURRENT_TIMESTAMP AND o.status='PENDING_PAYMENT' ORDER BY t.execute_at LIMIT 100",
                String.class)) {
            try {
                orders.cancel(0, no, "TIMEOUT");
            } catch (Exception e) {
                jdbc.update("UPDATE trade_order_timeout t JOIN trade_order o ON o.id=t.order_id SET t.retry_count=t.retry_count+1,t.updated_at=CURRENT_TIMESTAMP WHERE o.order_no=? AND t.status='PENDING'", no);
            }
        }
    }

    @Scheduled(fixedDelayString = "${geek.compensation.interval:5000}")
    void compensate() {
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT id,task_type,business_no,payload_json FROM trade_compensation_task WHERE
                """ + TradeTasks.DUE + " ORDER BY next_retry_at,id LIMIT 50")) {
            long id = ((Number) row.get("id")).longValue();
            String token = tasks.claim(id);
            if (token == null) continue;
            String type = String.valueOf(row.get("task_type"));
            String no = String.valueOf(row.get("business_no"));
            try {
                switch (type) {
                    case "RESERVE_STOCK" -> orders.resumeReservation(no);
                    case "CREATE_SECKILL_ORDER" -> seckillOrders.resume(no, id, token);
                    case "RELEASE_STOCK" -> {
                        if (no.startsWith("SK")) {
                            long orderId = jdbc.queryForObject("SELECT id FROM trade_order WHERE order_no=?", Long.class, no);
                            inventory.release(no, orderId);
                        } else {
                            inventory.release(no);
                        }
                        jdbc.update("UPDATE trade_order SET inventory_status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE order_no=? AND status='CANCELLED'", no);
                    }
                    case "DEDUCT_STOCK" -> {
                        inventory.deduct(no);
                        jdbc.update("UPDATE trade_order SET inventory_status='DEDUCTED',updated_at=CURRENT_TIMESTAMP WHERE order_no=? AND status IN ('PAID','PARTIAL_SHIPPED','SHIPPED','COMPLETED')", no);
                    }
                    case "SETTLE_ASSETS" -> {
                        var discounts = jdbc.queryForList("""
                                SELECT DISTINCT d.discount_type FROM trade_order_discount d
                                JOIN trade_order o ON o.id=d.order_id WHERE o.order_no=?
                                """, String.class, no);
                        if (discounts.contains("COUPON")) marketing.settleCoupon(no, "use");
                        if (discounts.contains("POINTS")) member.settlePoints(no, "use");
                    }
                    case "CREATE_FULFILLMENT" -> {
                        if (!"DEDUCTED".equals(jdbc.queryForObject(
                                "SELECT inventory_status FROM trade_order WHERE order_no=?", String.class, no))) {
                            jdbc.update("""
                                    UPDATE trade_compensation_task SET status='RETRY',lease_token=NULL,lease_until=NULL,
                                      next_retry_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP
                                    WHERE id=? AND lease_token=?
                                    """, id, token);
                            continue;
                        }
                        payments.createFulfillments(no);
                    }
                    case "REFUND" -> retryRefund(no);
                    case "ORDER_SETTLEMENT" -> settleOrder(no);
                    case "RELEASE_SECKILL" -> seckillOrders.release(no, String.valueOf(row.get("payload_json")));
                    case "RELEASE_ASSETS" -> {
                        marketing.settleCoupon(no, "release");
                        member.settlePoints(no, "release");
                    }
                    default -> {
                        tasks.finish(id, token, "MANUAL", "需要人工处理");
                        continue;
                    }
                }
                if (tasks.finish(id, token, "SUCCESS", null) == 1) {
                    jdbc.update("INSERT INTO trade_reconcile_log(id,reconcile_type,business_no,difference_count,status,detail,created_at) VALUES(?,?,?,0,'SUCCESS','补偿完成',CURRENT_TIMESTAMP)",
                            DistributedId.next(), type, no);
                }
            } catch (Exception e) {
                var attempts = jdbc.queryForList("SELECT retry_count FROM trade_compensation_task WHERE id=? AND lease_token=?",
                        Integer.class, id, token);
                if (attempts.isEmpty()) continue;
                int retry = attempts.getFirst() + 1;
                String error = String.valueOf(e.getMessage());
                jdbc.update("""
                        UPDATE trade_compensation_task SET status='RETRY',retry_count=?,
                          next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),last_error=?,
                          lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND lease_token=?
                        """, retry, 1L << Math.min(retry, 8),
                        error.substring(0, Math.min(490, error.length())), id, token);
            }
        }
    }

    private void retryRefund(String aftersaleNo) throws Exception {
        if (refunds.settle(aftersaleNo)) return;
        var task = jdbc.queryForMap("SELECT payload_json FROM trade_compensation_task WHERE task_type='REFUND' AND business_no=? ORDER BY updated_at DESC LIMIT 1", aftersaleNo);
        var payload = mapper.readValue(String.valueOf(task.get("payload_json")), java.util.Map.class);
        BigDecimal approved = payload.get("approvedAmount") == null ? null : new BigDecimal(String.valueOf(payload.get("approvedAmount")));
        refunds.refund(String.valueOf(payload.get("orderNo")), ((Number) payload.get("orderItemId")).longValue(), aftersaleNo,
                ((Number) payload.get("quantity")).intValue(), approved);
        if (!refunds.settle(aftersaleNo)) throw new IllegalStateException("退款记录尚未生成");
    }

    private void settleOrder(String orderNo) {
        for (var item : jdbc.queryForList("""
                SELECT i.id,s.merchant_id,i.payable_amount
                FROM trade_order_item i
                JOIN trade_sub_order s ON s.id=i.sub_order_id
                JOIN trade_order o ON o.id=i.order_id
                WHERE o.order_no=?
                """, orderNo)) {
            settlement.orderCompleted(((Number) item.get("merchant_id")).longValue(), orderNo,
                    ((Number) item.get("id")).longValue(), (BigDecimal) item.get("payable_amount"));
        }
    }
}
