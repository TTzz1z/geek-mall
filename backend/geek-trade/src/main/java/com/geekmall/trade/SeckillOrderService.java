package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class SeckillOrderService {
    private final JdbcTemplate jdbc;
    private final ProductSnapshotClient products;
    private final InventoryClient inventory;
    private final TradeEventStore events;
    private final MarketingClient marketing;
    private final MemberClient member;
    private final TradeTasks tasks;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final long timeoutSeconds;

    SeckillOrderService(JdbcTemplate jdbc, ProductSnapshotClient products, InventoryClient inventory,
                        TradeEventStore events, MarketingClient marketing, MemberClient member,
                        PlatformTransactionManager manager, TradeTasks tasks, ObjectMapper mapper,
                        @org.springframework.beans.factory.annotation.Value("${geek.seckill-timeout-seconds:900}") long timeoutSeconds) {
        this.jdbc = jdbc;
        this.products = products;
        this.inventory = inventory;
        this.events = events;
        this.marketing = marketing;
        this.member = member;
        this.tasks = tasks;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
        this.timeoutSeconds = timeoutSeconds;
    }

    // The consumer acknowledges only after this intent and its recovery task commit together.
    void enqueue(JsonNode payload) {
        String requestId = payload.path("requestId").asText();
        if (requestId.isBlank() || !payload.path("seckillOrderId").canConvertToLong()) {
            throw new BusinessException("TR40031", "秒杀请求标识缺失");
        }
        String orderNo = "SK" + payload.path("seckillOrderId").asLong();
        Instant expires = payload.hasNonNull("expiresAt") ? Instant.parse(payload.get("expiresAt").asText())
                : Instant.now().plusSeconds(timeoutSeconds);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO trade_seckill_progress(order_no,request_id,payload_json,expires_at,phase,updated_at)
                    VALUES(?,?,?,FROM_UNIXTIME(?),'RECEIVED',CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE order_no=order_no
                    """, orderNo, requestId, payload.toString(), BigDecimal.valueOf(expires.toEpochMilli(), 3));
            var rows = jdbc.queryForList("SELECT order_no,payload_json FROM trade_seckill_progress WHERE request_id=? FOR UPDATE", requestId);
            if (rows.isEmpty()) throw new BusinessException("TR40933", "秒杀请求标识与已有订单冲突");
            JsonNode original;
            try {
                original = mapper.readTree(String.valueOf(rows.getFirst().get("payload_json")));
            } catch (Exception failure) {
                throw new IllegalStateException("秒杀恢复快照无效", failure);
            }
            for (String field : List.of("seckillOrderId", "sessionId", "skuId", "userId", "quantity", "addressId")) {
                if (original.has(field) && !original.path(field).asText().equals(payload.path(field).asText())) {
                    throw new BusinessException("TR40933", "秒杀请求标识已用于不同订单请求");
                }
            }
            if (original.has("price") && new BigDecimal(original.path("price").asText()).compareTo(new BigDecimal(payload.path("price").asText())) != 0) {
                throw new BusinessException("TR40933", "秒杀请求标识已用于不同订单请求");
            }
            tasks.enqueue("CREATE_SECKILL_ORDER", String.valueOf(rows.getFirst().get("order_no")));
        });
    }

    void resume(String orderNo, long taskId, String token) throws Exception {
        var progress = jdbc.queryForMap("""
                SELECT request_id,payload_json,phase,UNIX_TIMESTAMP(expires_at) AS expires_epoch,
                    CASE WHEN expires_at<=CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS expired
                FROM trade_seckill_progress WHERE order_no=?
                """, orderNo);
        JsonNode payload = mapper.readTree(String.valueOf(progress.get("payload_json")));
        String phase = String.valueOf(progress.get("phase"));
        if (Set.of("COMPLETED", "CANCELLED").contains(phase)) return;
        try {
            var existing = jdbc.queryForList("SELECT status FROM trade_order WHERE order_no=?", String.class, orderNo);
            if (!existing.isEmpty() && !Set.of("RESERVING", "RESERVATION_RETRY", "PENDING_PAYMENT").contains(existing.getFirst())) return;
            Instant expires = Instant.ofEpochMilli(((BigDecimal) progress.get("expires_epoch")).movePointRight(3).longValueExact());
            // A retry after an ambiguous confirmation asks marketing for the existing result.
            if (((Number) progress.get("expired")).intValue() == 1 && Set.of("RECEIVED", "PREPARED").contains(phase)
                    && (existing.isEmpty() || !"PENDING_PAYMENT".equals(existing.getFirst()))) {
                throw new BusinessException("TR40931", "秒杀订单已过期");
            }
            if ("RECEIVED".equals(phase)) {
                prepare(orderNo, payload, taskId, token);
                phase = "PREPARED";
            }
            var order = jdbc.queryForMap("SELECT * FROM trade_order WHERE order_no=?", orderNo);
            long orderId = ((Number) order.get("id")).longValue();
            if ("PREPARED".equals(phase)) {
                reserve(orderNo, orderId, expires, taskId, token);
                phase = "STOCK_RESERVED";
            }
            if ("STOCK_RESERVED".equals(phase)) {
                marketing.markSeckillCreated(String.valueOf(progress.get("request_id")), orderNo);
                tx.executeWithoutResult(status -> {
                    requireLease(taskId, token);
                    jdbc.update("UPDATE trade_seckill_progress SET phase='MARKETING_CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
                });
            }
            tx.executeWithoutResult(status -> activate(orderNo, orderId, taskId, token));
        } catch (BusinessException failure) {
            if (!failure.code().contains("409") && !failure.code().contains("400") && !failure.code().contains("404")) throw failure;
            tx.executeWithoutResult(status -> cancel(orderNo, payload, taskId, token, failure.getMessage()));
        }
    }

    private void prepare(String orderNo, JsonNode payload, long taskId, String token) {
        long userId = payload.path("userId").asLong();
        long skuId = payload.path("skuId").asLong();
        long sessionId = payload.path("sessionId").asLong();
        int quantity = payload.path("quantity").asInt();
        if (!payload.hasNonNull("addressId") || quantity < 1) throw new BusinessException("TR40030", "秒杀下单必须选择收货地址和有效数量");
        long addressId = payload.path("addressId").asLong();
        Map<String, Object> addr = member.address(userId, addressId);
        SkuSnapshot sku = products.snapshots(List.of(skuId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("TR40401", "秒杀商品不存在"));
        BigDecimal price = new BigDecimal(payload.path("price").asText());
        BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity));
        tx.executeWithoutResult(status -> {
            requireLease(taskId, token);
            if (!"RECEIVED".equals(jdbc.queryForObject("SELECT phase FROM trade_seckill_progress WHERE order_no=? FOR UPDATE", String.class, orderNo))) return;
            long orderId = DistributedId.next();
            long subId = DistributedId.next();
            long itemId = DistributedId.next();
            String requestId = payload.path("requestId").asText();
            jdbc.update("""
                    INSERT INTO trade_idempotency(idempotency_key,user_id,business_type,request_hash,result_ref,expires_at,created_at)
                    VALUES(?,?,'SECKILL_ORDER',?,?,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)
                    """, requestId, userId, requestId, orderNo);
            jdbc.update("""
                    INSERT INTO trade_order(id,order_no,user_id,total_amount,discount_amount,freight_amount,payable_amount,status,checkout_token,inventory_status,created_at,updated_at)
                    VALUES(?,?,?,?,0,0,?,'RESERVING',?,'RESERVING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, orderId, orderNo, userId, subtotal, subtotal, "SECKILL-" + requestId);
            jdbc.update("""
                    INSERT INTO trade_sub_order(id,sub_order_no,order_id,merchant_id,shop_id,shop_name,total_amount,discount_amount,payable_amount,status,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,0,?,'RESERVING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, subId, orderNo + "-1", orderId, sku.merchantId(), sku.shopId(), sku.shopName(), subtotal, subtotal);
            jdbc.update("""
                    INSERT INTO trade_order_item(id,order_id,sub_order_id,spu_id,sku_id,title,cover_url,sku_snapshot,quantity,unit_price,discount_amount,payable_amount,created_at)
                    VALUES(?,?,?,?,?,?,?,'{}',?,?,0,?,CURRENT_TIMESTAMP)
                    """, itemId, orderId, subId, sku.spuId(), skuId, sku.title(), sku.coverUrl(), quantity, price, subtotal);
            jdbc.update("""
                    INSERT INTO trade_order_discount(id,order_id,order_item_id,discount_type,source_id,amount,snapshot_json)
                    VALUES(?,?,?,'SECKILL',?,0,?)
                    """, DistributedId.next(), orderId, itemId, String.valueOf(sessionId),
                    "{\"sessionId\":" + sessionId + ",\"skuId\":" + skuId + "}");
            jdbc.update("INSERT INTO trade_order_address(order_id,address_id,receiver,mobile,region_code,detail) VALUES(?,?,?,?,?,?)",
                    orderId, addressId, addr.get("receiver"), addr.get("mobile"), addr.get("regionCode"), addr.get("detail"));
            jdbc.update("UPDATE trade_seckill_progress SET phase='PREPARED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
        });
    }

    private void reserve(String orderNo, long orderId, Instant expires, long taskId, String token) {
        var item = jdbc.queryForMap("""
                SELECT i.sku_id,i.quantity,s.merchant_id,s.shop_id FROM trade_order_item i
                JOIN trade_sub_order s ON s.id=i.sub_order_id WHERE i.order_id=?
                """, orderId);
        String region = jdbc.queryForObject("SELECT region_code FROM trade_order_address WHERE order_id=?", String.class, orderId);
        var allocations = inventory.reserve(orderId, orderNo, region, expires, List.of(Map.of(
                "merchantId", item.get("merchant_id"), "shopId", item.get("shop_id"),
                "skuId", item.get("sku_id"), "quantity", item.get("quantity"))));
        if (allocations.isEmpty() || allocations.stream().anyMatch(a -> !"RESERVED".equals(a.status()))) {
            throw new BusinessException("TR40932", "秒杀库存预占已失效");
        }
        tx.executeWithoutResult(status -> {
            requireLease(taskId, token);
            Long subId = jdbc.queryForObject("SELECT id FROM trade_sub_order WHERE order_id=?", Long.class, orderId);
            for (StockAllocation a : allocations) {
                jdbc.update("INSERT IGNORE INTO trade_fulfillment_plan(id,order_id,sub_order_id,warehouse_id,sku_id,quantity,status) VALUES(?,?,?,?,?,?,'RESERVED')",
                        DistributedId.next(), orderId, subId, a.warehouseId(), a.skuId(), a.quantity());
            }
            jdbc.update("UPDATE trade_seckill_progress SET phase='STOCK_RESERVED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
        });
    }

    private void activate(String orderNo, long orderId, long taskId, String token) {
        requireLease(taskId, token);
        String current = jdbc.queryForObject("SELECT status FROM trade_order WHERE id=? FOR UPDATE", String.class, orderId);
        if (!Set.of("RESERVING", "RESERVATION_RETRY", "PENDING_PAYMENT").contains(current)) return;
        String phase = jdbc.queryForObject("SELECT phase FROM trade_seckill_progress WHERE order_no=? FOR UPDATE", String.class, orderNo);
        if ("COMPLETED".equals(phase)) return;
        if (!"MARKETING_CONFIRMED".equals(phase)) throw new IllegalStateException("秒杀营销确认尚未完成");
        jdbc.update("UPDATE trade_order SET status='PENDING_PAYMENT',inventory_status='RESERVED',updated_at=CURRENT_TIMESTAMP WHERE id=?", orderId);
        jdbc.update("UPDATE trade_sub_order SET status='PENDING_PAYMENT',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        jdbc.update("""
                INSERT INTO trade_order_timeout(id,order_id,timeout_type,execute_at,status,updated_at)
                SELECT ?,?,'PAYMENT',p.expires_at,'PENDING',CURRENT_TIMESTAMP FROM trade_seckill_progress p WHERE p.order_no=?
                ON DUPLICATE KEY UPDATE execute_at=trade_order_timeout.execute_at
                """, DistributedId.next(), orderId, orderNo);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id=? AND event_type='trade.order.created.v1'", Integer.class, orderNo) == 0) {
            BigDecimal amount = jdbc.queryForObject("SELECT payable_amount FROM trade_order WHERE id=?", BigDecimal.class, orderId);
            events.append("TRADE_ORDER", orderNo, "trade.order.created.v1", Map.of("orderId", orderId,
                    "orderNo", orderNo, "payableAmount", amount, "source", "SECKILL"));
        }
        jdbc.update("UPDATE trade_seckill_progress SET phase='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
    }

    private void cancel(String orderNo, JsonNode payload, long taskId, String token, String reason) {
        requireLease(taskId, token);
        var rows = jdbc.queryForList("SELECT id,status FROM trade_order WHERE order_no=? FOR UPDATE", orderNo);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!Set.of("RESERVING", "RESERVATION_RETRY", "PENDING_PAYMENT").contains(row.get("status"))) return;
            jdbc.update("UPDATE trade_order SET status='CANCELLED',inventory_status='RELEASE_PENDING',closed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
            jdbc.update("UPDATE trade_sub_order SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", row.get("id"));
            jdbc.update("UPDATE trade_order_timeout SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", row.get("id"));
            tasks.enqueue("RELEASE_STOCK", orderNo, reason);
        }
        tasks.enqueue("RELEASE_SECKILL", orderNo, payload.toString(), reason);
        jdbc.update("UPDATE trade_seckill_progress SET phase='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE order_no=?", orderNo);
    }

    void release(String orderNo, String payloadJson) throws Exception {
        JsonNode payload = mapper.readTree(payloadJson);
        if (payload.has("sessionId")) {
            marketing.releaseSeckill(payload.path("sessionId").asLong(), payload.path("skuId").asLong(),
                    payload.path("userId").asLong(), payload.path("quantity").asInt());
            return;
        }
        onTimeoutCancel(orderNo);
    }

    void onTimeoutCancel(String orderNo) throws Exception {
        var rows = jdbc.queryForList("""
                SELECT d.snapshot_json,o.user_id,i.quantity FROM trade_order_discount d
                JOIN trade_order o ON o.id=d.order_id JOIN trade_order_item i ON i.order_id=o.id
                WHERE o.order_no=? AND d.discount_type='SECKILL'
                """, orderNo);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        JsonNode node = mapper.readTree(String.valueOf(row.get("snapshot_json")));
        marketing.releaseSeckill(node.path("sessionId").asLong(), node.path("skuId").asLong(),
                ((Number) row.get("user_id")).longValue(), ((Number) row.get("quantity")).intValue());
    }

    private void requireLease(long taskId, String token) {
        if (!tasks.owns(taskId, token)) throw new IllegalStateException("秒杀恢复任务租约已转移");
    }
}
