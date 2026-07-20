package com.geekmall.inventory;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
class FulfillmentController {
    private final JdbcTemplate jdbc;
    private final String internalKey;
    private final RestClient aftersaleClient;
    private final RestClient tradeClient;

    FulfillmentController(JdbcTemplate jdbc,
                          @Value("${geek.internal-key}") String internalKey,
                          @Value("${geek.aftersale-service-url:http://localhost:9008}") String aftersaleUrl,
                          @Value("${geek.trade-service-url:http://localhost:9005}") String tradeUrl) {
        this.jdbc = jdbc;
        this.internalKey = internalKey;
        this.aftersaleClient = RestClient.builder().baseUrl(aftersaleUrl).build();
        this.tradeClient = RestClient.builder().baseUrl(tradeUrl).build();
    }

    @PostMapping("/internal/fulfillments")
    @Transactional
    ApiResponse<Map<String, Object>> create(@RequestHeader("X-Internal-Key") String key, @Valid @RequestBody Create b, HttpServletRequest r) {
        internal(key);
        var old = jdbc.queryForList("SELECT fulfillment_no AS fulfillmentNo FROM inventory_fulfillment WHERE order_no=? AND warehouse_id=?", b.orderNo(), b.warehouseId());
        if (!old.isEmpty()) return ok(old.getFirst(), r);
        long id = DistributedId.next();
        String no = "FF" + id;
        jdbc.update("INSERT INTO inventory_fulfillment(id,fulfillment_no,order_no,user_id,merchant_id,warehouse_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'PICKING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, no, b.orderNo(), b.userId(), b.merchantId(), b.warehouseId());
        for (Item i : b.items()) {
            jdbc.update("INSERT INTO inventory_fulfillment_item(id,fulfillment_id,order_item_id,sku_id,quantity) VALUES(?,?,?,?,?)",
                    DistributedId.next(), id, i.orderItemId(), i.skuId(), i.quantity());
        }
        return ok(Map.of("fulfillmentNo", no, "status", "PICKING"), r);
    }

    @GetMapping("/admin/fulfillments")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> adminList(HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT f.fulfillment_no AS fulfillmentNo,
                       f.order_no AS orderNo,
                       f.warehouse_id AS warehouseId,
                       f.status,
                       f.created_at AS createdAt,
                       (SELECT p.carrier_code FROM inventory_package p
                         WHERE p.fulfillment_id=f.id
                         ORDER BY p.created_at DESC LIMIT 1) AS carrierCode
                FROM inventory_fulfillment f
                ORDER BY f.created_at DESC
                LIMIT 100
                """), r);
    }

    @GetMapping("/admin/fulfillments/{no}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> adminDetail(@PathVariable String no, HttpServletRequest r) {
        var rows = jdbc.queryForList(
                "SELECT fulfillment_no AS fulfillmentNo,order_no AS orderNo,warehouse_id AS warehouseId,status,created_at AS createdAt FROM inventory_fulfillment WHERE fulfillment_no=?",
                no);
        if (rows.isEmpty()) throw new BusinessException("FL40402", "履约单不存在");
        var d = rows.getFirst();
        d.put("items", jdbc.queryForList(
                "SELECT order_item_id AS orderItemId,sku_id AS skuId,quantity,shipped_qty AS shippedQty FROM inventory_fulfillment_item WHERE fulfillment_id=(SELECT id FROM inventory_fulfillment WHERE fulfillment_no=?)",
                no));
        d.put("packages", jdbc.queryForList(
                "SELECT package_no AS packageNo,carrier_code AS carrierCode,tracking_no AS trackingNo,status,shipped_at AS shippedAt FROM inventory_package WHERE fulfillment_id=(SELECT id FROM inventory_fulfillment WHERE fulfillment_no=?)",
                no));
        return ok(d, r);
    }

    @PostMapping("/admin/fulfillments/{no}/force-split-ship")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    @Transactional
    ApiResponse<Map<String, Object>> forceSplitShip(@PathVariable String no, @Valid @RequestBody Ship b, HttpServletRequest r) {
        Map<String, Object> f = jdbc.queryForMap("SELECT * FROM inventory_fulfillment WHERE fulfillment_no=? FOR UPDATE", no);
        if (Set.of("SHIPPED", "COMPLETED").contains(String.valueOf(f.get("status")))) {
            throw new BusinessException("FL40901", "当前履约单不可发货");
        }
        long fulfillmentId = ((Number) f.get("id")).longValue();
        for (ShipLine line : b.lines()) {
            int changed = jdbc.update(
                    "UPDATE inventory_fulfillment_item SET shipped_qty=shipped_qty+? WHERE fulfillment_id=? AND order_item_id=? AND shipped_qty+?<=quantity",
                    line.quantity(), fulfillmentId, line.orderItemId(), line.quantity());
            if (changed == 0) throw new BusinessException("FL40902", "发货数量超过可发数量");
        }
        long id = DistributedId.next();
        String packageNo = "PK" + id;
        jdbc.update("INSERT INTO inventory_package(id,package_no,fulfillment_id,carrier_code,tracking_no,status,shipped_at,created_at) VALUES(?,?,?,?,?,'SHIPPED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, packageNo, fulfillmentId, b.carrierCode(), b.trackingNo());
        track(id, "SHIPPED", "运营强制拆单发货");
        boolean allShipped = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*)=0 FROM inventory_fulfillment_item WHERE fulfillment_id=? AND shipped_qty<quantity", Boolean.class, fulfillmentId));
        String status = allShipped ? "SHIPPED" : "PARTIAL_SHIPPED";
        jdbc.update("UPDATE inventory_fulfillment SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, fulfillmentId);
        if (allShipped) {
            notifyTradeOrder(String.valueOf(f.get("order_no")), "SHIPPED");
        }
        return ok(Map.of("packageNo", packageNo, "status", status), r);
    }

    @GetMapping("/merchant/fulfillments")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<List<Map<String, Object>>> list(HttpServletRequest r) {
        return ok(jdbc.queryForList(
                """
                SELECT f.fulfillment_no AS fulfillmentNo,f.order_no AS orderNo,f.warehouse_id AS warehouseId,f.status,f.created_at AS createdAt,
                       (SELECT p.carrier_code FROM inventory_package p WHERE p.fulfillment_id=f.id ORDER BY p.created_at DESC LIMIT 1) AS carrierCode
                FROM inventory_fulfillment f
                WHERE f.merchant_id=?
                ORDER BY f.created_at DESC
                """,
                TenantContext.requireMerchantId()), r);
    }

    @GetMapping("/merchant/fulfillments/{no}")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<Map<String, Object>> detail(@PathVariable String no, HttpServletRequest r) {
        long merchant = TenantContext.requireMerchantId();
        var rows = jdbc.queryForList(
                "SELECT fulfillment_no AS fulfillmentNo,order_no AS orderNo,warehouse_id AS warehouseId,status,created_at AS createdAt FROM inventory_fulfillment WHERE fulfillment_no=? AND merchant_id=?",
                no, merchant);
        if (rows.isEmpty()) throw new BusinessException("FL40402", "履约单不存在");
        var d = rows.getFirst();
        d.put("items", jdbc.queryForList(
                "SELECT order_item_id AS orderItemId,sku_id AS skuId,quantity,shipped_qty AS shippedQty FROM inventory_fulfillment_item WHERE fulfillment_id=(SELECT id FROM inventory_fulfillment WHERE fulfillment_no=?)",
                no));
        d.put("packages", jdbc.queryForList(
                "SELECT package_no AS packageNo,carrier_code AS carrierCode,tracking_no AS trackingNo,status,shipped_at AS shippedAt FROM inventory_package WHERE fulfillment_id=(SELECT id FROM inventory_fulfillment WHERE fulfillment_no=?)",
                no));
        return ok(d, r);
    }

    @PostMapping("/merchant/fulfillments/{no}/ship")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> ship(@PathVariable String no, @Valid @RequestBody Ship b, HttpServletRequest r) {
        long merchant = TenantContext.requireMerchantId();
        Map<String, Object> f = jdbc.queryForMap("SELECT * FROM inventory_fulfillment WHERE fulfillment_no=? AND merchant_id=? FOR UPDATE", no, merchant);
        if (Set.of("SHIPPED", "COMPLETED").contains(String.valueOf(f.get("status")))) {
            throw new BusinessException("FL40901", "当前履约单不可发货");
        }
        long fulfillmentId = ((Number) f.get("id")).longValue();
        for (ShipLine line : b.lines()) {
            int changed = jdbc.update(
                    "UPDATE inventory_fulfillment_item SET shipped_qty=shipped_qty+? WHERE fulfillment_id=? AND order_item_id=? AND shipped_qty+?<=quantity",
                    line.quantity(), fulfillmentId, line.orderItemId(), line.quantity());
            if (changed == 0) throw new BusinessException("FL40902", "发货数量超过可发数量");
        }
        long id = DistributedId.next();
        String packageNo = "PK" + id;
        jdbc.update("INSERT INTO inventory_package(id,package_no,fulfillment_id,carrier_code,tracking_no,status,shipped_at,created_at) VALUES(?,?,?,?,?,'SHIPPED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, packageNo, fulfillmentId, b.carrierCode(), b.trackingNo());
        track(id, "SHIPPED", "商家已发货，等待揽收");
        boolean allShipped = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*)=0 FROM inventory_fulfillment_item WHERE fulfillment_id=? AND shipped_qty<quantity", Boolean.class, fulfillmentId));
        String status = allShipped ? "SHIPPED" : "PARTIAL_SHIPPED";
        jdbc.update("UPDATE inventory_fulfillment SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, fulfillmentId);
        if (allShipped) {
            notifyTradeOrder(String.valueOf(f.get("order_no")), "SHIPPED");
        }
        return ok(Map.of("packageNo", packageNo, "status", status), r);
    }

    @PostMapping("/warehouse/fulfillments/{no}/receive-return")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> warehouseReceive(@PathVariable String no, @RequestParam String aftersaleNo, HttpServletRequest r) {
        long merchant = TenantContext.requireMerchantId();
        jdbc.queryForMap("SELECT id FROM inventory_fulfillment WHERE fulfillment_no=? AND merchant_id=?", no, merchant);
        try {
            aftersaleClient.post().uri("/api/v1/internal/aftersales/{no}/warehouse-receive", aftersaleNo)
                    .header("X-Internal-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new BusinessException("FL50301", "售后仓审联动失败");
        }
        return ok(Map.of("fulfillmentNo", no, "aftersaleNo", aftersaleNo, "warehouseStatus", "RECEIVED"), r);
    }

    @GetMapping("/logistics/packages/{no}")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<Map<String, Object>> packageDetail(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        var rows = jdbc.queryForList(
                "SELECT p.id,p.package_no AS packageNo,p.carrier_code AS carrierCode,p.tracking_no AS trackingNo,p.status,p.shipped_at AS shippedAt,p.received_at AS receivedAt,f.order_no AS orderNo FROM inventory_package p JOIN inventory_fulfillment f ON f.id=p.fulfillment_id WHERE p.package_no=? AND f.user_id=?",
                no, p.userId());
        if (rows.isEmpty()) throw new BusinessException("FL40401", "物流包裹不存在");
        var d = rows.getFirst();
        d.put("tracks", jdbc.queryForList(
                "SELECT node_code AS nodeCode,detail,occurred_at AS occurredAt FROM inventory_logistics_track WHERE package_id=? ORDER BY occurred_at",
                d.get("id")));
        d.remove("id");
        return ok(d, r);
    }

    @PostMapping("/logistics/packages/{no}/receive")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> receive(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        var rows = jdbc.queryForList(
                "SELECT p.id,p.status,f.id AS fulfillmentId,f.order_no AS orderNo FROM inventory_package p JOIN inventory_fulfillment f ON f.id=p.fulfillment_id WHERE p.package_no=? AND f.user_id=? FOR UPDATE",
                no, p.userId());
        if (rows.isEmpty()) throw new BusinessException("FL40401", "物流包裹不存在");
        var row = rows.getFirst();
        if ("RECEIVED".equals(row.get("status"))) return ok(Map.of("packageNo", no, "idempotentReplay", true), r);
        jdbc.update("UPDATE inventory_package SET status='RECEIVED',received_at=CURRENT_TIMESTAMP WHERE id=?", row.get("id"));
        track(((Number) row.get("id")).longValue(), "RECEIVED", "用户确认收货");
        long fulfillmentId = ((Number) row.get("fulfillmentId")).longValue();
        boolean allReceived = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*)=0 FROM inventory_package WHERE fulfillment_id=? AND status<>'RECEIVED'", Boolean.class, fulfillmentId));
        if (allReceived) {
            jdbc.update("UPDATE inventory_fulfillment SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=?", fulfillmentId);
            notifyTradeOrder(String.valueOf(row.get("orderNo")), "COMPLETED");
        }
        return ok(Map.of("packageNo", no, "status", "RECEIVED", "idempotentReplay", false), r);
    }

    private void notifyTradeOrder(String orderNo, String status) {
        try {
            tradeClient.post()
                    .uri(uri -> uri.path("/api/v1/internal/trade/orders/{orderNo}/fulfillment-status")
                            .queryParam("status", status).build(orderNo))
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new BusinessException("FL50301", "回写交易订单状态失败: " + e.getMessage());
        }
    }

    private void track(long id, String node, String detail) {
        jdbc.update("INSERT INTO inventory_logistics_track(id,package_id,node_code,detail,occurred_at,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                DistributedId.next(), id, node, detail);
    }

    private void internal(String key) {
        com.geekmall.common.security.InternalServiceAccess.require(key, internalKey);
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record Item(@NotNull Long orderItemId, @NotNull Long skuId, @Min(1) int quantity) {}

    record Create(@NotBlank String orderNo, @NotNull Long userId, @NotNull Long merchantId, @NotNull Long warehouseId,
                  @NotEmpty List<@Valid Item> items) {}

    record ShipLine(@NotNull Long orderItemId, @Min(1) int quantity) {}

    record Ship(@NotBlank String carrierCode, @NotBlank String trackingNo, @NotEmpty List<@Valid ShipLine> lines) {}
}
