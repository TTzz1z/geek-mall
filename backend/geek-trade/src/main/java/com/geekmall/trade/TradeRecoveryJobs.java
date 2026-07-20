package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
    private final ObjectMapper mapper;

    TradeRecoveryJobs(JdbcTemplate jdbc, OrderService orders, InventoryClient inventory, RefundService refunds,
                      MarketingClient marketing, MemberClient member, PaymentService payments, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.inventory = inventory;
        this.refunds = refunds;
        this.marketing = marketing;
        this.member = member;
        this.payments = payments;
        this.mapper = mapper;
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
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,task_type,business_no,retry_count,payload_json FROM trade_compensation_task WHERE status IN ('PENDING','RETRY') AND next_retry_at<=CURRENT_TIMESTAMP ORDER BY next_retry_at LIMIT 50")) {
            long id = ((Number) row.get("id")).longValue();
            if (jdbc.update("UPDATE trade_compensation_task SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RETRY')", id) == 0) {
                continue;
            }
            String type = String.valueOf(row.get("task_type"));
            String no = String.valueOf(row.get("business_no"));
            try {
                switch (type) {
                    case "RESERVE_STOCK" -> orders.resumeReservation(no);
                    case "RELEASE_STOCK" -> {
                        inventory.release(no);
                        jdbc.update("UPDATE trade_order SET inventory_status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", no);
                    }
                    case "DEDUCT_STOCK" -> {
                        inventory.deduct(no);
                        jdbc.update("UPDATE trade_order SET inventory_status='DEDUCTED',updated_at=CURRENT_TIMESTAMP WHERE order_no=? AND status IN ('PAID','SHIPPED','COMPLETED')", no);
                    }
                    case "SETTLE_ASSETS" -> {
                        marketing.settleCoupon(no, "use");
                        member.settlePoints(no, "use");
                    }
                    case "CREATE_FULFILLMENT" -> payments.createFulfillments(no);
                    case "REFUND" -> retryRefund(no);
                    default -> {
                        jdbc.update("UPDATE trade_compensation_task SET status='MANUAL',last_error='需要人工处理',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                        continue;
                    }
                }
                jdbc.update("UPDATE trade_compensation_task SET status='SUCCESS',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                jdbc.update("INSERT INTO trade_reconcile_log(id,reconcile_type,business_no,difference_count,status,detail,created_at) VALUES(?,?,?,0,'SUCCESS','补偿完成',CURRENT_TIMESTAMP)",
                        DistributedId.next(), type, no);
            } catch (Exception e) {
                int retry = ((Number) row.get("retry_count")).intValue() + 1;
                jdbc.update("UPDATE trade_compensation_task SET status=?,retry_count=?,next_retry_at=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        retry >= 8 ? "FAILED" : "RETRY", retry,
                        Timestamp.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(retry, 8)))),
                        String.valueOf(e.getMessage()).substring(0, Math.min(490, String.valueOf(e.getMessage()).length())), id);
            }
        }
    }

    private void retryRefund(String aftersaleNo) throws Exception {
        var task = jdbc.queryForMap("SELECT payload_json FROM trade_compensation_task WHERE task_type='REFUND' AND business_no=? ORDER BY updated_at DESC LIMIT 1", aftersaleNo);
        var payload = mapper.readValue(String.valueOf(task.get("payload_json")), java.util.Map.class);
        BigDecimal approved = payload.get("approvedAmount") == null ? null : new BigDecimal(String.valueOf(payload.get("approvedAmount")));
        refunds.refund(String.valueOf(payload.get("orderNo")), ((Number) payload.get("orderItemId")).longValue(), aftersaleNo,
                ((Number) payload.get("quantity")).intValue(), approved);
    }
}
