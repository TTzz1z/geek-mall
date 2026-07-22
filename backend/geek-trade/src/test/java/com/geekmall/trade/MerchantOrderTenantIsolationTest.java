package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantOrderTenantIsolationTest {
    private MerchantOrderService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:merchant_orders_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE trade_order (
                    id BIGINT PRIMARY KEY, order_no VARCHAR(64), inventory_status VARCHAR(32),
                    created_at TIMESTAMP, paid_at TIMESTAMP, closed_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE trade_sub_order (
                    id BIGINT PRIMARY KEY, sub_order_no VARCHAR(64), order_id BIGINT,
                    merchant_id BIGINT, shop_id BIGINT, shop_name VARCHAR(128),
                    total_amount DECIMAL(18,2), discount_amount DECIMAL(18,2),
                    payable_amount DECIMAL(18,2), status VARCHAR(32), remark VARCHAR(500)
                )
                """);
        jdbc.execute("""
                CREATE TABLE trade_order_item (
                    id BIGINT PRIMARY KEY, order_id BIGINT, sub_order_id BIGINT,
                    spu_id BIGINT, sku_id BIGINT, title VARCHAR(255), cover_url VARCHAR(500),
                    sku_snapshot VARCHAR(1000), quantity INT, unit_price DECIMAL(18,2),
                    discount_amount DECIMAL(18,2), payable_amount DECIMAL(18,2), aftersale_qty INT
                )
                """);

        jdbc.update("INSERT INTO trade_order VALUES (1, 'GM-ONE', 'DEDUCTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)");
        jdbc.update("INSERT INTO trade_order VALUES (2, 'GM-TWO', 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)");
        jdbc.update("INSERT INTO trade_sub_order VALUES (11, 'GM-ONE-1', 1, 2001, 3001, 'A shop', 100, 0, 100, 'PAID', NULL)");
        jdbc.update("INSERT INTO trade_sub_order VALUES (12, 'GM-TWO-1', 2, 2002, 3002, 'B shop', 200, 0, 200, 'PAID', NULL)");
        jdbc.update("INSERT INTO trade_order_item VALUES (101, 1, 11, 501, 601, 'A item', NULL, '{}', 1, 100, 0, 100, 0)");
        jdbc.update("INSERT INTO trade_order_item VALUES (102, 2, 12, 502, 602, 'B item', NULL, '{}', 1, 200, 0, 200, 0)");
        service = new MerchantOrderService(jdbc);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listUsesRealTradeTablesAndOnlyReturnsCurrentMerchantSubOrders() {
        TenantContext.setMerchantId(2001L);

        List<Map<String, Object>> orders = service.orders();

        assertThat(orders).singleElement().satisfies(order -> {
            assertThat(order).containsEntry("orderNo", "GM-ONE")
                    .containsEntry("subOrderNo", "GM-ONE-1")
                    .containsEntry("merchantId", 2001L);
            assertThat((List<?>) order.get("items")).singleElement()
                    .satisfies(item -> assertThat(((Map<?, ?>) item).get("title")).isEqualTo("A item"));
        });
        assertThat(orders.toString()).doesNotContain("GM-TWO", "B item");
    }

    @Test
    void crossTenantSubOrderDetailIsNotVisible() {
        TenantContext.setMerchantId(2001L);

        assertThatThrownBy(() -> service.detail("GM-TWO-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TR40410");
    }
}
