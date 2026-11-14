package com.geekmall.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SeckillWarmupJob {
    private static final Logger log = LoggerFactory.getLogger(SeckillWarmupJob.class);
    private final JdbcTemplate jdbc;
    private final SeckillInventoryService seckillInventory;

    SeckillWarmupJob(JdbcTemplate jdbc, SeckillInventoryService seckillInventory) {
        this.jdbc = jdbc;
        this.seckillInventory = seckillInventory;
    }

    @Scheduled(fixedDelayString = "${geek.seckill.warmup-interval:30000}")
    void warmupActiveSessions() {
        for (var row : jdbc.queryForList(
                "SELECT i.session_id AS sessionId,i.sku_id AS skuId FROM marketing_seckill_item i JOIN marketing_seckill_session s ON s.id=i.session_id WHERE s.status='ENABLED' AND s.start_at<=CURRENT_TIMESTAMP AND s.end_at>CURRENT_TIMESTAMP AND i.status='ENABLED' AND i.warm_status='COLD' LIMIT 20")) {
            try {
                seckillInventory.warm(((Number) row.get("sessionId")).longValue(), ((Number) row.get("skuId")).longValue());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Expire QUEUED reservations while Trade has yet to confirm the order.
     * CREATED stock is released exclusively by Trade cancel / payment timeout.
     */
    @Scheduled(fixedDelayString = "${geek.seckill.timeout-interval:5000}")
    void releaseExpired() {
        for (var row : jdbc.queryForList("""
                SELECT id,session_id,sku_id FROM marketing_seckill_order
                WHERE status='QUEUED' AND expires_at<=CURRENT_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 50
                """)) {
            long orderId = ((Number) row.get("id")).longValue();
            try {
                seckillInventory.releaseExpired(((Number) row.get("session_id")).longValue(),
                        ((Number) row.get("sku_id")).longValue(), orderId);
            } catch (RuntimeException e) {
                log.warn("Failed to release expired seckill reservation orderId={}", orderId, e);
            }
        }
    }
}
