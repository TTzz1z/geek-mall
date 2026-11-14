package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
class SeckillInventoryService {
    private final JdbcTemplate jdbc;
    private final RedisLuaClient redis;
    private final TransactionTemplate tx;

    SeckillInventoryService(JdbcTemplate jdbc, RedisLuaClient redis, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.tx = new TransactionTemplate(manager);
    }

    void release(long sessionId, long skuId, long userId, int quantity) {
        release(sessionId, skuId, userId, quantity, false);
    }

    void releaseExpired(long sessionId, long skuId, long userId, int quantity) {
        release(sessionId, skuId, userId, quantity, true);
    }

    private void release(long sessionId, long skuId, long userId, int quantity, boolean expiredOnly) {
        Long orderId = tx.execute(status -> beginRelease(sessionId, skuId, userId, quantity, expiredOnly));
        if (orderId == null) return;
        String stock = "seckill:stock:" + sessionId + ":" + skuId;
        String limit = "seckill:user:" + sessionId + ":" + skuId + ":" + userId;
        // Redis may commit while the following SQL transaction is lost. Keep the
        // release receipt with the counters so replay cannot restore stock twice.
        redis.eval("""
                if redis.call('EXISTS',KEYS[3]) == 1 then return 0 end
                if redis.call('EXISTS',KEYS[1]) == 1 then redis.call('INCRBY',KEYS[1],ARGV[1]) end
                local used=redis.call('GET',KEYS[2])
                if used then redis.call('SET',KEYS[2],math.max(0,tonumber(used)-tonumber(ARGV[1])),'KEEPTTL') end
                redis.call('SET',KEYS[3],'1')
                return 1
                """, List.of(stock, limit, "seckill:release:" + orderId), List.of(String.valueOf(quantity)));
        jdbc.update("UPDATE marketing_seckill_order SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RELEASE_PENDING'", orderId);
    }

    private Long beginRelease(long sessionId, long skuId, long userId, int quantity, boolean expiredOnly) {
        String eligible = expiredOnly ? "status='QUEUED' AND expires_at<=CURRENT_TIMESTAMP"
                : "status IN ('QUEUED','CREATED','TIMEOUT')";
        var rows = jdbc.queryForList("""
                SELECT id,quantity,status FROM marketing_seckill_order WHERE session_id=? AND sku_id=? AND user_id=?
                  AND (status='RELEASE_PENDING' OR (
                """ + eligible + ")) FOR UPDATE", sessionId, skuId, userId);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (((Number) row.get("quantity")).intValue() != quantity) {
            throw new BusinessException("MK40906", "秒杀释放数量与原请求不一致");
        }
        long orderId = ((Number) row.get("id")).longValue();
        if (!"RELEASE_PENDING".equals(row.get("status"))) {
            jdbc.update("UPDATE marketing_seckill_order SET status='RELEASE_PENDING',updated_at=CURRENT_TIMESTAMP WHERE id=?", orderId);
        }
        return orderId;
    }

    void undoUncommittedHold(long sessionId, long skuId, long userId, int quantity) {
        String stock = "seckill:stock:" + sessionId + ":" + skuId;
        String limit = "seckill:user:" + sessionId + ":" + skuId + ":" + userId;
        redis.eval("redis.call('INCRBY',KEYS[1],ARGV[1]); redis.call('DECRBY',KEYS[2],ARGV[1]); return 1",
                List.of(stock, limit), List.of(String.valueOf(quantity)));
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
