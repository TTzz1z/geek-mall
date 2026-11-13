package com.geekmall.trade;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RefundRecoveryTest {
    @Test
    void settlementFailureLeavesRefundPendingAndRetryUsesTheSameRefundNumber() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:refund_recovery;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("""
                CREATE TABLE trade_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),status VARCHAR(32),paid_payment_id BIGINT);
                CREATE TABLE trade_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,quantity INT,
                    aftersale_qty INT,payable_amount DECIMAL(18,2));
                CREATE TABLE trade_refund(id BIGINT PRIMARY KEY,refund_no VARCHAR(64),payment_id BIGINT,order_id BIGINT,
                    order_item_id BIGINT,aftersale_id VARCHAR(64) UNIQUE,quantity INT,amount DECIMAL(18,2),
                    status VARCHAR(32),channel_refund_no VARCHAR(96),created_at TIMESTAMP,updated_at TIMESTAMP,succeeded_at TIMESTAMP);
                CREATE TABLE trade_compensation_task(id BIGINT PRIMARY KEY,task_type VARCHAR(64),business_no VARCHAR(64),
                    payload_json VARCHAR(100),status VARCHAR(32),next_retry_at TIMESTAMP,last_error VARCHAR(500),
                    created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(task_type,business_no));
                """);
        jdbc.update("INSERT INTO trade_order VALUES(1,'GM1','SHIPPED',9)");
        jdbc.update("INSERT INTO trade_order_item VALUES(2,1,2,0,100.00)");
        var settlement = mock(SettlementClient.class);
        var refunds = new RefundService(jdbc, settlement, new TradeTasks(jdbc, 120));
        var amount = new BigDecimal("50.00");
        var pending = tx.execute(s -> refunds.refund("GM1", 2, "AS1", 1, amount));
        assertThat(pending).containsEntry("status", "PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_compensation_task", Integer.class)).isEqualTo(1);
        verifyNoInteractions(settlement);

        String refundNo = (String) pending.get("refundNo");
        doThrow(new IllegalStateException("settlement unavailable")).doNothing()
                .when(settlement).refund(refundNo, 2, amount);
        assertThatThrownBy(() -> refunds.settle("AS1")).hasMessage("settlement unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_refund", String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(succeeded_at) FROM trade_refund", Integer.class)).isZero();

        assertThat(refunds.settle("AS1")).isTrue();
        assertThat(refunds.settle("AS1")).isTrue();
        var replay = tx.execute(s -> refunds.refund("GM1", 2, "AS1", 1, amount));
        assertThat(replay)
                .containsEntry("refundNo", refundNo).containsEntry("status", "SUCCESS").containsEntry("idempotentReplay", true);
        assertThat(jdbc.queryForObject("SELECT aftersale_qty FROM trade_order_item", Integer.class)).isEqualTo(1);
        verify(settlement, times(2)).refund(refundNo, 2, amount);
    }
}
