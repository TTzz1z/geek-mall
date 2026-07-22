package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
class MerchantOrderService {
    private static final String SELECT_ORDER = """
            SELECT s.id, o.order_no AS orderNo, s.sub_order_no AS subOrderNo,
                   s.merchant_id AS merchantId, s.shop_id AS shopId, s.shop_name AS shopName,
                   s.total_amount AS totalAmount, s.discount_amount AS discountAmount,
                   s.payable_amount AS payableAmount, s.status, s.remark,
                   o.inventory_status AS inventoryStatus, o.created_at AS createdAt,
                   o.paid_at AS paidAt, o.closed_at AS closedAt
            FROM trade_sub_order s
            JOIN trade_order o ON o.id = s.order_id
            WHERE s.merchant_id = ?
            """;

    private final JdbcTemplate jdbc;

    MerchantOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Map<String, Object>> orders() {
        long merchantId = TenantContext.requireMerchantId();
        List<Map<String, Object>> orders = jdbc.queryForList(
                SELECT_ORDER + " ORDER BY o.created_at DESC, s.sub_order_no DESC", merchantId);
        orders.forEach(order -> attachItems(order, merchantId));
        return orders;
    }

    Map<String, Object> detail(String subOrderNo) {
        long merchantId = TenantContext.requireMerchantId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_ORDER + " AND s.sub_order_no = ?", merchantId, subOrderNo);
        if (rows.isEmpty()) {
            throw new BusinessException("TR40410", "商家子订单不存在");
        }
        Map<String, Object> order = rows.getFirst();
        attachItems(order, merchantId);
        return order;
    }

    private void attachItems(Map<String, Object> order, long merchantId) {
        long subOrderId = ((Number) order.remove("id")).longValue();
        order.put("items", jdbc.queryForList("""
                SELECT i.id, i.spu_id AS spuId, i.sku_id AS skuId, i.title,
                       i.cover_url AS coverUrl, i.sku_snapshot AS skuSnapshot,
                       i.quantity, i.unit_price AS unitPrice,
                       i.discount_amount AS discountAmount, i.payable_amount AS payableAmount,
                       i.aftersale_qty AS aftersaleQty
                FROM trade_order_item i
                JOIN trade_sub_order owner ON owner.id = i.sub_order_id
                WHERE i.sub_order_id = ? AND owner.merchant_id = ?
                ORDER BY i.id
                """, subOrderId, merchantId));
    }
}
