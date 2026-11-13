package com.geekmall.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
class InventoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    InventoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    List<Map<String, Object>> warehouses() {
        return jdbc.queryForList("""
                SELECT w.id,
                       w.warehouse_code AS warehouseCode,
                       w.warehouse_name AS warehouseName,
                       w.region_code AS regionCode,
                       w.address,
                       w.priority,
                       w.status,
                       COALESCE(agg.sku_types, 0) AS skuTypes,
                       COALESCE(agg.physical_total, 0) AS physicalTotal,
                       CASE
                         WHEN w.priority >= 100 THEN 82
                         WHEN w.priority >= 80 THEN 45
                         ELSE LEAST(90, GREATEST(12, w.priority / 2))
                       END AS utilizationRate
                FROM inventory_warehouse w
                LEFT JOIN (
                    SELECT warehouse_id,
                           COUNT(*) AS sku_types,
                           SUM(physical_qty) AS physical_total
                    FROM inventory_sku_stock
                    GROUP BY warehouse_id
                ) agg ON agg.warehouse_id = w.id
                ORDER BY w.priority DESC
                """);
    }

    List<Map<String, Object>> stocks(Long warehouseId) {
        long merchantId = TenantContext.requireMerchantId();
        String sql = "SELECT s.id,s.sku_id AS skuId,s.warehouse_id AS warehouseId,w.warehouse_name AS warehouseName,s.physical_qty AS physicalQty,s.locked_qty AS lockedQty,(s.physical_qty-s.locked_qty-s.defective_qty) AS availableQty,s.in_transit_qty AS inTransitQty,s.defective_qty AS defectiveQty,s.warning_qty AS warningQty,s.version FROM inventory_sku_stock s JOIN inventory_warehouse w ON w.id=s.warehouse_id WHERE s.merchant_id=?";
        return warehouseId == null ? jdbc.queryForList(sql + " ORDER BY s.sku_id,w.priority DESC", merchantId)
                : jdbc.queryForList(sql + " AND s.warehouse_id=? ORDER BY s.sku_id", merchantId, warehouseId);
    }

    List<Map<String, Object>> logs(Long skuId) {
        long merchantId = TenantContext.requireMerchantId();
        String sql = "SELECT id,warehouse_id AS warehouseId,sku_id AS skuId,business_type AS businessType,business_no AS businessNo,change_physical AS changePhysical,change_locked AS changeLocked,before_physical AS beforePhysical,after_physical AS afterPhysical,operator_id AS operatorId,created_at AS createdAt FROM inventory_stock_log WHERE merchant_id=?";
        return skuId == null ? jdbc.queryForList(sql + " ORDER BY created_at DESC LIMIT 200", merchantId)
                : jdbc.queryForList(sql + " AND sku_id=? ORDER BY created_at DESC LIMIT 200", merchantId, skuId);
    }

    List<Map<String, Object>> availability(List<InventoryController.AvailabilityItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var item : items) {
            Integer available = jdbc.queryForObject("SELECT COALESCE(SUM(physical_qty-locked_qty-defective_qty),0) FROM inventory_sku_stock WHERE merchant_id=? AND sku_id=?", Integer.class, item.merchantId(), item.skuId());
            List<Map<String,Object>> warehouses = jdbc.queryForList("SELECT s.warehouse_id AS warehouseId,w.warehouse_name AS warehouseName,w.region_code AS regionCode,w.priority,(s.physical_qty-s.locked_qty-s.defective_qty) AS availableQty FROM inventory_sku_stock s JOIN inventory_warehouse w ON w.id=s.warehouse_id WHERE s.merchant_id=? AND s.sku_id=? AND w.status='ENABLED' AND s.physical_qty-s.locked_qty-s.defective_qty>0 ORDER BY w.priority DESC,s.warehouse_id", item.merchantId(), item.skuId());
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("merchantId", item.merchantId()); row.put("skuId", item.skuId()); row.put("requested", item.quantity());
            row.put("availableQty", Objects.requireNonNullElse(available, 0)); row.put("available", available != null && available >= item.quantity()); row.put("warehouses", warehouses);
            result.add(row);
        }
        return result;
    }

    @Transactional
    Map<String, Object> inbound(InventoryController.InboundRequest body, String key, AuthPrincipal principal) {
        if (key == null || key.isBlank()) throw new BusinessException("IV40002", "入库必须携带 Idempotency-Key");
        long merchantId = TenantContext.requireMerchantId();
        String businessNo = "IN" + System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO inventory_idempotency(idempotency_key,merchant_id,business_type,result_ref,created_at) VALUES(?,?,'INBOUND',?,CURRENT_TIMESTAMP)", key, merchantId, businessNo);
        } catch (DuplicateKeyException exception) {
            String existing = jdbc.queryForObject("SELECT result_ref FROM inventory_idempotency WHERE idempotency_key=? AND merchant_id=? AND business_type='INBOUND'", String.class, key, merchantId);
            return Map.of("businessNo", existing, "idempotentReplay", true);
        }
        Map<String, Object> stock = findOrCreate(merchantId, body.warehouseId(), body.skuId());
        int before = number(stock.get("physical_qty"));
        int version = number(stock.get("version"));
        int changed = jdbc.update("UPDATE inventory_sku_stock SET physical_qty=physical_qty+?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?", body.quantity(), stock.get("id"), version);
        if (changed == 0) throw new BusinessException("IV40902", "库存并发更新失败，请重试");
        jdbc.update("INSERT INTO inventory_stock_log(id,merchant_id,warehouse_id,sku_id,business_type,business_no,change_physical,before_physical,after_physical,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", DistributedId.next(), merchantId, body.warehouseId(), body.skuId(), "INBOUND", businessNo, body.quantity(), before, before + body.quantity(), principal.userId());
        return Map.of("businessNo", businessNo, "idempotentReplay", false, "afterPhysical", before + body.quantity());
    }

    @Transactional
    Map<String, Object> reserve(InventoryController.ReservationRequest request) {
        jdbc.update("""
                INSERT INTO inventory_reservation_lock(order_id) VALUES(?)
                ON DUPLICATE KEY UPDATE order_id=order_id
                """, request.orderId());
        List<Map<String, Object>> existing = reservations(request.orderNo());
        if (!existing.isEmpty()) return Map.of("orderNo", request.orderNo(), "reservationNo", "RS" + request.orderId(), "idempotentReplay", true, "allocations", existing);

        List<InventoryController.ReservationItem> orderedItems = request.items().stream()
                .sorted(Comparator.comparing(InventoryController.ReservationItem::skuId).thenComparing(InventoryController.ReservationItem::merchantId)).toList();
        for (var item : orderedItems) {
            int remaining = item.quantity();
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                    SELECT s.id,s.warehouse_id,s.physical_qty,s.locked_qty,s.defective_qty,w.warehouse_name
                    FROM inventory_sku_stock s JOIN inventory_warehouse w ON w.id=s.warehouse_id
                    WHERE s.merchant_id=? AND s.sku_id=? AND w.status='ENABLED'
                    ORDER BY CASE WHEN w.region_code=? THEN 0 ELSE 1 END,w.priority DESC,s.warehouse_id
                    """, item.merchantId(), item.skuId(), Objects.requireNonNullElse(request.regionCode(), ""));
            for (var candidate : candidates) {
                if (remaining == 0) break;
                int available = number(candidate.get("physical_qty")) - number(candidate.get("locked_qty")) - number(candidate.get("defective_qty"));
                if (available <= 0) continue;
                int allocation = Math.min(available, remaining);
                int changed = jdbc.update("UPDATE inventory_sku_stock SET locked_qty=locked_qty+?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND physical_qty-locked_qty-defective_qty>=?", allocation, candidate.get("id"), allocation);
                if (changed == 0) {
                    Integer current = jdbc.queryForObject("SELECT physical_qty-locked_qty-defective_qty FROM inventory_sku_stock WHERE id=?", Integer.class, candidate.get("id"));
                    allocation = Math.min(Math.max(Objects.requireNonNullElse(current, 0), 0), remaining);
                    if (allocation == 0 || jdbc.update("UPDATE inventory_sku_stock SET locked_qty=locked_qty+?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND physical_qty-locked_qty-defective_qty>=?", allocation, candidate.get("id"), allocation) == 0) continue;
                }
                long warehouseId = ((Number) candidate.get("warehouse_id")).longValue();
                jdbc.update("INSERT INTO inventory_stock_reservation(id,reservation_no,order_id,order_no,merchant_id,warehouse_id,sku_id,quantity,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'RESERVED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", DistributedId.next(), "RS" + request.orderId(), request.orderId(), request.orderNo(), item.merchantId(), warehouseId, item.skuId(), allocation, java.sql.Timestamp.from(request.expiresAt()));
                stockLog(item.merchantId(), warehouseId, item.skuId(), "RESERVE", request.orderNo(), 0, allocation);
                remaining -= allocation;
            }
            if (remaining > 0) throw new BusinessException("IV40901", "SKU " + item.skuId() + " 可售库存不足");
        }
        event(request.orderNo(), "inventory.stock.reserved.v1", Map.of("orderNo", request.orderNo(), "reservationNo", "RS" + request.orderId()));
        return Map.of("orderNo", request.orderNo(), "reservationNo", "RS" + request.orderId(), "idempotentReplay", false, "allocations", reservations(request.orderNo()));
    }

    @Transactional
    Map<String, Object> release(String orderNo) {
        int affected = 0;
        for (var row : jdbc.queryForList("SELECT id,merchant_id,warehouse_id,sku_id,quantity FROM inventory_stock_reservation WHERE order_no=? AND status='RESERVED' ORDER BY warehouse_id,sku_id FOR UPDATE", orderNo)) {
            int changed = jdbc.update("UPDATE inventory_stock_reservation SET status='RELEASED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RESERVED'", row.get("id"));
            if (changed == 0) continue;
            int quantity = number(row.get("quantity"));
            jdbc.update("UPDATE inventory_sku_stock SET locked_qty=locked_qty-?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE warehouse_id=? AND sku_id=? AND merchant_id=? AND locked_qty>=?", quantity, row.get("warehouse_id"), row.get("sku_id"), row.get("merchant_id"), quantity);
            stockLog(((Number) row.get("merchant_id")).longValue(), ((Number) row.get("warehouse_id")).longValue(), ((Number) row.get("sku_id")).longValue(), "RELEASE", orderNo, 0, -quantity);
            affected++;
        }
        if (affected > 0) event(orderNo, "inventory.stock.released.v1", Map.of("orderNo", orderNo));
        return Map.of("orderNo", orderNo, "released", affected, "idempotentReplay", affected == 0);
    }

    @Transactional
    Map<String, Object> deduct(String orderNo) {
        int affected = 0;
        for (var row : jdbc.queryForList("SELECT id,merchant_id,warehouse_id,sku_id,quantity FROM inventory_stock_reservation WHERE order_no=? AND status='RESERVED' ORDER BY warehouse_id,sku_id FOR UPDATE", orderNo)) {
            int changed = jdbc.update("UPDATE inventory_stock_reservation SET status='DEDUCTED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RESERVED'", row.get("id"));
            if (changed == 0) continue;
            int quantity = number(row.get("quantity"));
            int stockChanged = jdbc.update("UPDATE inventory_sku_stock SET physical_qty=physical_qty-?,locked_qty=locked_qty-?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE warehouse_id=? AND sku_id=? AND merchant_id=? AND physical_qty>=? AND locked_qty>=?", quantity, quantity, row.get("warehouse_id"), row.get("sku_id"), row.get("merchant_id"), quantity, quantity);
            if (stockChanged == 0) throw new BusinessException("IV40903", "预占库存扣减状态异常");
            stockLog(((Number) row.get("merchant_id")).longValue(), ((Number) row.get("warehouse_id")).longValue(), ((Number) row.get("sku_id")).longValue(), "DEDUCT", orderNo, -quantity, -quantity);
            affected++;
        }
        if (affected == 0) {
            var rows = jdbc.queryForList("""
                    SELECT status FROM inventory_stock_reservation WHERE order_no=? FOR UPDATE
                    """, String.class, orderNo);
            if (rows.isEmpty() || rows.stream().anyMatch(status -> !"DEDUCTED".equals(status))) {
                throw new BusinessException("IV40904", "没有可扣减的有效库存预占");
            }
        }
        return Map.of("orderNo", orderNo, "deducted", affected, "idempotentReplay", affected == 0);
    }

    List<Map<String, Object>> reservations(String orderNo) {
        return jdbc.queryForList("SELECT reservation_no AS reservationNo,order_id AS orderId,order_no AS orderNo,merchant_id AS merchantId,warehouse_id AS warehouseId,sku_id AS skuId,quantity,status,expires_at AS expiresAt FROM inventory_stock_reservation WHERE order_no=? ORDER BY warehouse_id,sku_id", orderNo);
    }

    Map<String, Object> lowStockSummary() {
        Integer skuCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_sku_stock
                WHERE (physical_qty - locked_qty - defective_qty) <= warning_qty
                """, Integer.class);
        Integer lineCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_sku_stock
                WHERE (physical_qty - locked_qty - defective_qty) <= warning_qty
                """, Integer.class);
        List<Map<String, Object>> samples = jdbc.queryForList("""
                SELECT sku_id AS skuId, merchant_id AS merchantId, warehouse_id AS warehouseId,
                       (physical_qty - locked_qty - defective_qty) AS availableQty, warning_qty AS warningQty
                FROM inventory_sku_stock
                WHERE (physical_qty - locked_qty - defective_qty) <= warning_qty
                ORDER BY availableQty ASC, sku_id ASC
                LIMIT 10
                """);
        int count = skuCount == null ? 0 : skuCount;
        return Map.of(
                "lowStockSkuCount", count,
                "lowStockLineCount", lineCount == null ? 0 : lineCount,
                "source", "inventory_sku_stock",
                "samples", samples
        );
    }

    private void stockLog(long merchantId, long warehouseId, long skuId, String type, String businessNo, int physical, int locked) {
        Map<String, Object> stock = jdbc.queryForMap("SELECT physical_qty,locked_qty FROM inventory_sku_stock WHERE merchant_id=? AND warehouse_id=? AND sku_id=?", merchantId, warehouseId, skuId);
        int afterPhysical = number(stock.get("physical_qty"));
        jdbc.update("INSERT INTO inventory_stock_log(id,merchant_id,warehouse_id,sku_id,business_type,business_no,change_physical,change_locked,before_physical,after_physical,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP)", DistributedId.next(), merchantId, warehouseId, skuId, type, businessNo, physical, locked, afterPhysical - physical, afterPhysical);
    }

    private void event(String aggregateId, String type, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = Map.of("eventId", eventId, "eventType", type, "occurredAt", Instant.now().toString(), "producer", "geek-inventory-fulfillment", "aggregateId", aggregateId, "version", 1, "payload", payload);
        try {
            jdbc.update("INSERT INTO event_outbox(id,event_id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP)", DistributedId.next(), eventId, "STOCK_RESERVATION", aggregateId, type, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> findOrCreate(long merchantId, long warehouseId, long skuId) {
        var rows = jdbc.queryForList("SELECT id,physical_qty,version FROM inventory_sku_stock WHERE merchant_id=? AND warehouse_id=? AND sku_id=?", merchantId, warehouseId, skuId);
        if (!rows.isEmpty()) return rows.getFirst();
        jdbc.update("INSERT INTO inventory_sku_stock(id,merchant_id,warehouse_id,sku_id,created_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", DistributedId.next(), merchantId, warehouseId, skuId);
        return jdbc.queryForMap("SELECT id,physical_qty,version FROM inventory_sku_stock WHERE merchant_id=? AND warehouse_id=? AND sku_id=?", merchantId, warehouseId, skuId);
    }

    private static int number(Object value) { return ((Number) value).intValue(); }
}
