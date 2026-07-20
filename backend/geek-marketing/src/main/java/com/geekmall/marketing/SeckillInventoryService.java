package com.geekmall.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SeckillInventoryService {
    private final JdbcTemplate jdbc;
    private final RedisLuaClient redis;

    SeckillInventoryService(JdbcTemplate jdbc, RedisLuaClient redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Transactional
    void release(long sessionId, long skuId, long userId, int quantity) {
        int changed = jdbc.update(
                "UPDATE marketing_seckill_order SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND sku_id=? AND user_id=? AND status IN ('QUEUED','CREATED','TIMEOUT')",
                sessionId, skuId, userId);
        if (changed == 0) {
            return;
        }
        String stock = "seckill:stock:" + sessionId + ":" + skuId;
        String limit = "seckill:user:" + sessionId + ":" + skuId + ":" + userId;
        redis.eval("redis.call('INCRBY',KEYS[1],ARGV[1]); redis.call('DECRBY',KEYS[2],ARGV[1]); return 1",
                java.util.List.of(stock, limit), java.util.List.of(String.valueOf(quantity)));
    }

    /**
     * Warm Redis stock only when key is missing. Never overwrite an active HOT key with DB stock_qty,
     * which would erase in-flight Lua decrements and cause oversell.
     */
    void warm(long sessionId, long skuId) {
        String key = "seckill:stock:" + sessionId + ":" + skuId;
        String existing = redis.get(key);
        if (existing != null) {
            jdbc.update("UPDATE marketing_seckill_item SET warm_status='HOT' WHERE session_id=? AND sku_id=?", sessionId, skuId);
            redis.expire(key, 7200);
            return;
        }
        var rows = jdbc.queryForList("SELECT stock_qty FROM marketing_seckill_item WHERE session_id=? AND sku_id=? AND status='ENABLED'", sessionId, skuId);
        if (rows.isEmpty()) {
            return;
        }
        int stock = ((Number) rows.getFirst().get("stock_qty")).intValue();
        redis.set(key, String.valueOf(stock));
        redis.expire(key, 7200);
        jdbc.update("UPDATE marketing_seckill_item SET warm_status='HOT' WHERE session_id=? AND sku_id=?", sessionId, skuId);
    }
}
