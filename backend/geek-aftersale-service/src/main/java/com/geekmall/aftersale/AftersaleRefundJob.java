package com.geekmall.aftersale;

import com.geekmall.common.domain.DistributedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

@Component
class AftersaleRefundJob {
    private static final Logger log = LoggerFactory.getLogger(AftersaleRefundJob.class);
    private static final String DUE = """
            status='REFUNDING' AND (refund_retry_at IS NULL OR refund_retry_at<=CURRENT_TIMESTAMP)
            """;
    private final JdbcTemplate jdbc;
    private final TradeRefundClient refunds;
    private final TransactionTemplate tx;

    AftersaleRefundJob(JdbcTemplate jdbc, TradeRefundClient refunds, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.refunds = refunds;
        this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${geek.refund.interval:2000}")
    void advanceRefunds() {
        var rows = jdbc.queryForList("""
                SELECT id,aftersale_no,order_no,order_item_id,quantity,approved_amount,refund_retry_count
                FROM aftersale_order WHERE
                """ + DUE + " ORDER BY refund_retry_at,id LIMIT 20");
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            int attempts = ((Number) row.get("refund_retry_count")).intValue();
            int delay = 2 << Math.min(attempts, 7);
            if (jdbc.update("""
                    UPDATE aftersale_order SET refund_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),
                      refund_retry_count=refund_retry_count+1 WHERE id=? AND refund_retry_count=? AND
                    """ + DUE, Math.max(delay, 15), id, attempts) == 0) continue;
            String no = String.valueOf(row.get("aftersale_no"));
            try {
                String status = refunds.refund(String.valueOf(row.get("order_no")), ((Number) row.get("order_item_id")).longValue(),
                        no, ((Number) row.get("quantity")).intValue(), (BigDecimal) row.get("approved_amount"));
                if ("SUCCESS".equals(status)) {
                    tx.executeWithoutResult(s -> finish(id, "COMPLETED", "退款已成功"));
                } else if ("REJECTED".equals(status)) {
                    tx.executeWithoutResult(s -> finish(id, "REJECTED", "交易服务拒绝退款请求"));
                } else if ("PROCESSING".equals(status)) {
                    jdbc.update("""
                            UPDATE aftersale_order SET refund_retry_count=0,
                              refund_retry_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP)
                            WHERE id=? AND status='REFUNDING' AND refund_retry_count=?
                            """, id, attempts + 1);
                } else {
                    log.warn("Unexpected trade refund status for aftersale {}", no);
                }
            } catch (RuntimeException e) {
                log.warn("Refund sync failed for aftersale {} ({})", no, e.getClass().getSimpleName());
            }
        }
    }

    private void finish(long id, String status, String reason) {
        if (jdbc.update("""
                UPDATE aftersale_order SET status=?,refund_retry_at=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='REFUNDING'
                """, status, id) == 1) {
            jdbc.update("""
                    INSERT INTO aftersale_log(id,aftersale_id,from_status,to_status,operator_type,operator_id,reason,created_at)
                    VALUES(?,?,'REFUNDING',?,'SYSTEM',0,?,CURRENT_TIMESTAMP)
                    """, DistributedId.next(), id, status, reason);
        }
    }
}
