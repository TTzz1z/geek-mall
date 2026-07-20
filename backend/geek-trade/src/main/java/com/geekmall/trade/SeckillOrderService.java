package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
class SeckillOrderService {
    private final JdbcTemplate jdbc;
    private final ProductSnapshotClient products;
    private final InventoryClient inventory;
    private final TradeEventStore events;
    private final MarketingClient marketing;
    private final MemberClient member;
    private final TransactionTemplate tx;
    private final long timeoutSeconds;

    SeckillOrderService(JdbcTemplate jdbc, ProductSnapshotClient products, InventoryClient inventory,
                        TradeEventStore events, MarketingClient marketing, MemberClient member,
                        org.springframework.transaction.PlatformTransactionManager manager,
                        @org.springframework.beans.factory.annotation.Value("${geek.seckill-timeout-seconds:900}") long timeoutSeconds) {
        this.jdbc = jdbc;
        this.products = products;
        this.inventory = inventory;
        this.events = events;
        this.marketing = marketing;
        this.member = member;
        this.tx = new TransactionTemplate(manager);
        this.timeoutSeconds = timeoutSeconds;
    }

    void createFromEvent(Map<String, Object> payload) {
        String requestId = String.valueOf(payload.get("requestId"));
        if (!jdbc.queryForList("SELECT result_ref FROM trade_idempotency WHERE idempotency_key=? AND business_type='SECKILL_ORDER'", requestId).isEmpty()) {
            return;
        }
        long seckillOrderId = ((Number) payload.get("seckillOrderId")).longValue();
        long sessionId = ((Number) payload.get("sessionId")).longValue();
        long skuId = ((Number) payload.get("skuId")).longValue();
        long userId = ((Number) payload.get("userId")).longValue();
        int quantity = ((Number) payload.get("quantity")).intValue();
        BigDecimal price = new BigDecimal(String.valueOf(payload.get("price")));
        Long addressId = payload.get("addressId") == null ? null : ((Number) payload.get("addressId")).longValue();
        if (addressId == null) {
            throw new BusinessException("TR40030", "秒杀下单必须选择收货地址");
        }
        Map<String, Object> addr = member.address(userId, addressId);
        SkuSnapshot sku = products.snapshots(List.of(skuId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("TR40401", "秒杀商品不存在"));

        long orderId = DistributedId.next();
        String orderNo = "SK" + orderId;
        BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity));
        long subId = DistributedId.next();
        String regionCode = String.valueOf(addr.get("regionCode"));

        Boolean prepared = tx.execute(status -> {
            try {
                jdbc.update("INSERT INTO trade_idempotency(idempotency_key,user_id,business_type,request_hash,result_ref,expires_at,created_at) VALUES(?,?,'SECKILL_ORDER',?, ?,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY),CURRENT_TIMESTAMP)",
                        requestId, userId, requestId, orderNo);
            } catch (DuplicateKeyException e) {
                return false;
            }
            jdbc.update("INSERT INTO trade_order(id,order_no,user_id,total_amount,discount_amount,freight_amount,payable_amount,status,checkout_token,inventory_status,created_at,updated_at) VALUES(?,?,?,?,0,0,?,'RESERVING',?,'RESERVING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    orderId, orderNo, userId, subtotal, subtotal, "SECKILL-" + requestId);
            jdbc.update("INSERT INTO trade_sub_order(id,sub_order_no,order_id,merchant_id,shop_id,shop_name,total_amount,discount_amount,payable_amount,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?, 'RESERVING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    subId, orderNo + "-1", orderId, sku.merchantId(), sku.shopId(), sku.shopName(), subtotal, subtotal);
            long itemId = DistributedId.next();
            jdbc.update("INSERT INTO trade_order_item(id,order_id,sub_order_id,spu_id,sku_id,title,cover_url,sku_snapshot,quantity,unit_price,discount_amount,payable_amount,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,CURRENT_TIMESTAMP)",
                    itemId, orderId, subId, sku.spuId(), sku.skuId(), sku.title(), sku.coverUrl(), "{}", quantity, price, subtotal);
            jdbc.update("INSERT INTO trade_order_discount(id,order_id,order_item_id,discount_type,source_id,amount,snapshot_json) VALUES(?,?,?,?,?,?,?)",
                    DistributedId.next(), orderId, itemId, "SECKILL", String.valueOf(sessionId), BigDecimal.ZERO,
                    "{\"sessionId\":" + sessionId + ",\"skuId\":" + skuId + "}");
            jdbc.update("INSERT INTO trade_order_address(order_id,address_id,receiver,mobile,region_code,detail) VALUES(?,?,?,?,?,?)",
                    orderId, addressId, String.valueOf(addr.get("receiver")), String.valueOf(addr.get("mobile")),
                    regionCode, String.valueOf(addr.get("detail")));
            return true;
        });
        if (!Boolean.TRUE.equals(prepared)) {
            return;
        }

        Instant expires = Instant.now().plusSeconds(timeoutSeconds);
        List<Map<String, Object>> items = List.of(Map.of(
                "merchantId", sku.merchantId(), "shopId", sku.shopId(), "skuId", sku.skuId(), "quantity", quantity));
        try {
            List<StockAllocation> allocations = inventory.reserve(orderId, orderNo, regionCode, expires, items);
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE trade_order SET status='PENDING_PAYMENT',inventory_status='RESERVED',updated_at=CURRENT_TIMESTAMP WHERE id=?", orderId);
                jdbc.update("UPDATE trade_sub_order SET status='PENDING_PAYMENT',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
                for (StockAllocation a : allocations) {
                    jdbc.update("INSERT IGNORE INTO trade_fulfillment_plan(id,order_id,sub_order_id,warehouse_id,sku_id,quantity,status) VALUES(?,?,?,?,?,?,'RESERVED')",
                            DistributedId.next(), orderId, subId, a.warehouseId(), a.skuId(), a.quantity());
                }
                jdbc.update("INSERT INTO trade_order_timeout(id,order_id,timeout_type,execute_at,status,updated_at) VALUES(?,?,'PAYMENT',?,'PENDING',CURRENT_TIMESTAMP)",
                        DistributedId.next(), orderId, java.sql.Timestamp.from(expires));
            });
            marketing.markSeckillCreated(seckillOrderId, orderNo);
            events.append("TRADE_ORDER", orderNo, "trade.order.created.v1",
                    Map.of("orderId", orderId, "orderNo", orderNo, "payableAmount", subtotal, "source", "SECKILL"));
        } catch (RuntimeException e) {
            scheduleRelease(orderNo, e.getMessage());
            try {
                marketing.releaseSeckill(sessionId, skuId, userId, quantity);
            } catch (Exception ignored) {
            }
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE trade_order SET status='CANCELLED',inventory_status='RELEASE_PENDING',closed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('RESERVING','PENDING_PAYMENT')", orderId);
                jdbc.update("UPDATE trade_sub_order SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
            });
            throw e;
        }
    }

    void onTimeoutCancel(String orderNo) {
        var rows = jdbc.queryForList(
                "SELECT d.snapshot_json FROM trade_order_discount d JOIN trade_order o ON o.id=d.order_id WHERE o.order_no=? AND d.discount_type='SECKILL'",
                orderNo);
        if (rows.isEmpty()) {
            return;
        }
        try {
            var node = new ObjectMapper().readTree(String.valueOf(rows.getFirst().get("snapshot_json")));
            marketing.releaseSeckill(node.get("sessionId").asLong(), node.get("skuId").asLong(),
                    jdbc.queryForObject("SELECT user_id FROM trade_order WHERE order_no=?", Long.class, orderNo),
                    jdbc.queryForObject(
                            "SELECT quantity FROM trade_order_item i JOIN trade_order o ON o.id=i.order_id WHERE o.order_no=? LIMIT 1",
                            Integer.class, orderNo));
        } catch (Exception ignored) {
        }
    }

    private void scheduleRelease(String orderNo, String error) {
        jdbc.update(
                "INSERT INTO trade_compensation_task(id,task_type,business_no,payload_json,status,next_retry_at,last_error,created_at,updated_at) VALUES(?,?,?,'{}','PENDING',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status='PENDING',next_retry_at=CURRENT_TIMESTAMP,last_error=VALUES(last_error),updated_at=CURRENT_TIMESTAMP",
                DistributedId.next(), "RELEASE_STOCK", orderNo, error == null ? "seckill reserve failed" : error);
    }
}
