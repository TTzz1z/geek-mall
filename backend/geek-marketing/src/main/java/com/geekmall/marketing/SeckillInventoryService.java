package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class SeckillInventoryService {
    private final JdbcTemplate jdbc;
    private final MarketingEventStore events;
    private final TransactionTemplate tx;

    SeckillInventoryService(JdbcTemplate jdbc, MarketingEventStore events, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.events = events;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    Admission order(long sessionId, long skuId, long userId, int quantity, Long addressId, String requestId) {
        if (addressId == null || addressId <= 0) {
            throw new BusinessException("MK40030", "秒杀下单必须选择有效收货地址");
        }
        if (sessionId <= 0 || skuId <= 0 || userId <= 0 || quantity <= 0) {
            throw new BusinessException("MK40031", "秒杀下单参数无效");
        }
        if (requestId == null || requestId.isBlank() || requestId.codePointCount(0, requestId.length()) > 64) {
            throw new BusinessException("MK40031", "秒杀请求标识无效");
        }

        var request = new OrderRequest(sessionId, skuId, userId, quantity, addressId, requestId);
        try {
            return tx.execute(status -> reserve(request));
        } catch (DuplicateKeyException e) {
            // The request key is global. A competing order for another item can win
            // the unique index even while this transaction holds its own item lock.
            var existing = replay(request);
            if (existing != null) return existing;
            throw e;
        }
    }

    private Admission reserve(OrderRequest request) {
        var existing = replay(request);
        if (existing != null) return existing;

        var item = lockItem(request.sessionId(), request.skuId());
        // A retry may have committed while this transaction waited for the item lock.
        existing = replay(request);
        if (existing != null) return existing;

        if (item == null || !"ENABLED".equals(item.status())) {
            throw new BusinessException("MK40401", "秒杀商品或场次不可用");
        }
        var expiry = jdbc.query("""
                SELECT UNIX_TIMESTAMP(TIMESTAMPADD(SECOND,900,CURRENT_TIMESTAMP(3))) AS expires_epoch
                FROM marketing_seckill_session
                WHERE id=? AND status='ENABLED' AND start_at<=CURRENT_TIMESTAMP(3) AND end_at>CURRENT_TIMESTAMP(3)
                """, (rs, n) -> rs.getBigDecimal("expires_epoch"), request.sessionId());
        if (expiry.isEmpty()) throw new BusinessException("MK40401", "秒杀商品或场次不可用");
        BigDecimal expires = expiry.getFirst();

        if (request.quantity() > item.limitPerUser()) {
            throw new BusinessException("MK40903", "超过单人限购");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM marketing_seckill_order WHERE session_id=? AND sku_id=? AND user_id=?)
                """, Boolean.class, request.sessionId(), request.skuId(), request.userId()))) {
            throw new BusinessException("MK40904", "秒杀请求重复");
        }

        int held = jdbc.update("""
                UPDATE marketing_seckill_item SET reserved_qty=reserved_qty+?,warm_status='HOT'
                WHERE session_id=? AND sku_id=? AND stock_qty-reserved_qty>=?
                """, request.quantity(), request.sessionId(), request.skuId(), request.quantity());
        if (held == 0) throw new BusinessException("MK40903", "秒杀库存不足");

        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO marketing_seckill_order(id,session_id,sku_id,user_id,quantity,request_id,address_id,status,expires_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'QUEUED',FROM_UNIXTIME(?),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, request.sessionId(), request.skuId(), request.userId(), request.quantity(),
                request.requestId(), request.addressId(), expires);
        events.append("SECKILL_ORDER", String.valueOf(id), MarketingRabbitConfiguration.SECKILL_ROUTING, Map.of(
                "seckillOrderId", id, "sessionId", request.sessionId(), "skuId", request.skuId(),
                "userId", request.userId(), "quantity", request.quantity(), "requestId", request.requestId(),
                "price", item.price(), "addressId", request.addressId(),
                "expiresAt", Instant.ofEpochMilli(expires.movePointRight(3).longValueExact()).toString()));
        return new Admission(id, "QUEUED", null, request.requestId(), false);
    }

    private Admission replay(OrderRequest request) {
        var rows = jdbc.queryForList("""
                SELECT id,session_id,sku_id,user_id,quantity,request_id,address_id,status,trade_order_no
                FROM marketing_seckill_order WHERE request_id=?
                """, request.requestId());
        if (rows.isEmpty()) return null;

        var row = rows.getFirst();
        var originalAddress = (Number) row.get("address_id");
        if (!request.requestId().equals(row.get("request_id"))
                || request.sessionId() != ((Number) row.get("session_id")).longValue()
                || request.skuId() != ((Number) row.get("sku_id")).longValue()
                || request.userId() != ((Number) row.get("user_id")).longValue()
                || request.quantity() != ((Number) row.get("quantity")).intValue()
                || (originalAddress != null && request.addressId() != originalAddress.longValue())) {
            throw new BusinessException("MK40904", "秒杀请求标识已用于不同订单请求");
        }
        return new Admission(((Number) row.get("id")).longValue(), (String) row.get("status"),
                (String) row.get("trade_order_no"), request.requestId(), true);
    }

    void release(long sessionId, long skuId, long userId, int quantity) {
        tx.executeWithoutResult(status -> {
            lockItem(sessionId, skuId);
            var rows = jdbc.query("""
                    SELECT id,quantity FROM marketing_seckill_order WHERE session_id=? AND sku_id=? AND user_id=?
                    AND status IN ('QUEUED','CREATED','TIMEOUT') FOR UPDATE
                    """, (rs, n) -> new Release(rs.getLong("id"), rs.getInt("quantity")), sessionId, skuId, userId);
            if (rows.isEmpty()) return;
            var order = rows.getFirst();
            if (order.quantity() != quantity) {
                throw new BusinessException("MK40906", "秒杀释放数量与原请求不一致");
            }
            restore(sessionId, skuId, order);
        });
    }

    void releaseExpired(long sessionId, long skuId, long orderId) {
        tx.executeWithoutResult(status -> {
            lockItem(sessionId, skuId);
            var rows = jdbc.query("""
                    SELECT id,quantity FROM marketing_seckill_order WHERE id=? AND session_id=? AND sku_id=?
                    AND status='QUEUED' AND expires_at<=CURRENT_TIMESTAMP(3) FOR UPDATE
                    """, (rs, n) -> new Release(rs.getLong("id"), rs.getInt("quantity")), orderId, sessionId, skuId);
            if (!rows.isEmpty()) restore(sessionId, skuId, rows.getFirst());
        });
    }

    private Item lockItem(long sessionId, long skuId) {
        var items = jdbc.query("""
                SELECT status,price,limit_per_user FROM marketing_seckill_item WHERE session_id=? AND sku_id=? FOR UPDATE
                """, (rs, n) -> new Item(rs.getString("status"), rs.getBigDecimal("price"), rs.getInt("limit_per_user")),
                sessionId, skuId);
        return items.isEmpty() ? null : items.getFirst();
    }

    private void restore(long sessionId, long skuId, Release order) {
        if (order.quantity() > 0) {
            int restored = jdbc.update("""
                    UPDATE marketing_seckill_item SET reserved_qty=reserved_qty-?
                    WHERE session_id=? AND sku_id=? AND reserved_qty>=?
                    """, order.quantity(), sessionId, skuId, order.quantity());
            if (restored != 1) throw new IllegalStateException("Missing seckill stock reservation for order " + order.id());
        }
        jdbc.update("UPDATE marketing_seckill_order SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=?", order.id());
    }

    void warm(long sessionId, long skuId) {
        jdbc.update("""
                UPDATE marketing_seckill_item SET warm_status='HOT'
                WHERE session_id=? AND sku_id=? AND status='ENABLED' AND warm_status='COLD'
                """, sessionId, skuId);
    }

    record Admission(long id, String status, String tradeOrderNo, String requestId, boolean replay) {
        Map<String, Object> response(boolean publicApi) {
            if (replay) {
                var result = new LinkedHashMap<String, Object>();
                result.put("seckillOrderId", id);
                result.put("status", status);
                result.put("tradeOrderNo", tradeOrderNo);
                result.put("idempotentReplay", true);
                return result;
            }
            return publicApi ? Map.of("seckillOrderId", id, "status", status, "requestId", requestId)
                    : Map.of("seckillOrderId", id, "status", status, "idempotentReplay", false);
        }
    }

    private record OrderRequest(long sessionId, long skuId, long userId, int quantity, long addressId, String requestId) {}
    private record Item(String status, BigDecimal price, int limitPerUser) {}
    private record Release(long id, int quantity) {}
}
