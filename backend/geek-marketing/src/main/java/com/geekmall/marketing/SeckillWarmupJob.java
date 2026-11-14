package com.geekmall.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SeckillWarmupJob {
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
     * Only release QUEUED (Lua held, trade order not created yet).
     * CREATED stock is released exclusively by Trade cancel / payment timeout.
     */
    @Scheduled(fixedDelayString = "${geek.seckill.timeout-interval:5000}")
    void releaseExpired() {
        for (var row : jdbc.queryForList(
                "SELECT session_id AS sessionId,sku_id AS skuId,user_id AS userId,quantity FROM marketing_seckill_order WHERE status='RELEASE_PENDING' OR (status='QUEUED' AND expires_at<=CURRENT_TIMESTAMP) ORDER BY updated_at,id LIMIT 50")) {
            try {
                seckillInventory.releaseExpired(((Number) row.get("sessionId")).longValue(), ((Number) row.get("skuId")).longValue(),
                        ((Number) row.get("userId")).longValue(), ((Number) row.get("quantity")).intValue());
            } catch (Exception ignored) {
            }
        }
    }
}
