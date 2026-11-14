package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LatePaymentRecoveryTest {
    JdbcTemplate jdbc;
    PaymentService service;
    PaymentChannelAdapter adapter;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:late_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE trade_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),status VARCHAR(32),paid_payment_id BIGINT)");
        jdbc.execute("CREATE TABLE trade_payment(id BIGINT PRIMARY KEY,payment_no VARCHAR(64),order_id BIGINT,user_id BIGINT,channel VARCHAR(32),merchant_no VARCHAR(64),amount DECIMAL(18,2),status VARCHAR(32),channel_trade_no VARCHAR(96),created_at TIMESTAMP,updated_at TIMESTAMP,paid_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_payment_callback(id BIGINT PRIMARY KEY,channel VARCHAR(32),callback_id VARCHAR(96),payload_hash VARCHAR(64),payment_no VARCHAR(64),process_status VARCHAR(32),detail VARCHAR(500),created_at TIMESTAMP,processed_at TIMESTAMP,UNIQUE(channel,callback_id))");
        jdbc.execute("CREATE TABLE trade_compensation_task(id BIGINT PRIMARY KEY,task_type VARCHAR(64),business_no VARCHAR(64),payload_json VARCHAR(100),status VARCHAR(32),retry_count INT DEFAULT 0,next_retry_at TIMESTAMP,last_error VARCHAR(500),created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(task_type,business_no))");
        jdbc.execute("CREATE TABLE trade_late_payment_return(payment_no VARCHAR(64) PRIMARY KEY,order_no VARCHAR(64),channel VARCHAR(32),channel_trade_no VARCHAR(96),amount DECIMAL(18,2),return_no VARCHAR(64),status VARCHAR(32),channel_return_no VARCHAR(96),retry_count INT DEFAULT 0,last_error VARCHAR(500),created_at TIMESTAMP,updated_at TIMESTAMP,succeeded_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_simulated_payment_return(payment_no VARCHAR(64) PRIMARY KEY,channel_trade_no VARCHAR(96),amount DECIMAL(18,2),channel_return_no VARCHAR(96),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO trade_order VALUES(1,'GM-CLOSED','CANCELLED',NULL)");
        for (int i = 1; i <= 2; i++) jdbc.update("INSERT INTO trade_payment VALUES(?, ?,1,4,'SIMULATED','GEEK-MALL',100,'PENDING',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)", i, "PAY" + i);
        adapter = spy(new SimulatedPaymentChannelAdapter(jdbc));
        service = new PaymentService(jdbc, new DataSourceTransactionManager(ds), new UnavailableRealPaymentAdapters(List.of(adapter)), mock(InventoryClient.class), new TradeEventStore(jdbc, new ObjectMapper()), new TradeTasks(jdbc, 120), "GEEK-MALL", "secret");
    }

    @Test void eachLatePaymentHasIndependentDurableReturnAndFailureCallbackCannotRegressSuccess() throws Exception {
        callback("CB1", "PAY1", "SUCCESS", "T1");
        callback("CB2", "PAY2", "SUCCESS", "T2");
        callback("CB3", "PAY1", "FAILED", "OLD-FAILURE");
        assertThat(jdbc.queryForList("SELECT business_no FROM trade_compensation_task WHERE task_type='PAYMENT_AFTER_CLOSE'", String.class)).containsExactlyInAnyOrder("PAY1", "PAY2");
        assertThat(jdbc.queryForObject("SELECT channel_trade_no FROM trade_payment WHERE payment_no='PAY1'", String.class)).isEqualTo("T1");
        service.returnLatePayment("PAY1");
        service.returnLatePayment("PAY1");
        service.returnLatePayment("PAY2");
        callback("CB4", "PAY1", "SUCCESS", "T1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_simulated_payment_return", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM trade_late_payment_return", String.class)).containsOnly("SUCCESS");
        assertThat(jdbc.queryForList("SELECT status FROM trade_payment", String.class)).containsOnly("LATE_RETURNED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order", String.class)).isEqualTo("CANCELLED");
    }

    @Test void channelCommitThenLostResponseIsRecoveredByQueryWithoutSecondReturn() throws Exception {
        callback("CB1", "PAY1", "SUCCESS", "T1");
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("response lost"); })
                .when(adapter).returnPayment("PAY1", "T1", new BigDecimal("100.00"));
        assertThatThrownBy(() -> service.returnLatePayment("PAY1")).hasMessage("response lost");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_late_payment_return", String.class)).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_simulated_payment_return", Integer.class)).isEqualTo(1);
        service.returnLatePayment("PAY1");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_late_payment_return", String.class)).isEqualTo("SUCCESS");
        verify(adapter, times(1)).returnPayment("PAY1", "T1", new BigDecimal("100.00"));
    }

    void callback(String callbackId, String no, String state, String tradeNo) throws Exception {
        var body = new PaymentController.Callback(callbackId, no, "GEEK-MALL", new BigDecimal("100.00"), state, tradeNo);
        String canonical = "SIMULATED|" + callbackId + "|" + no + "|GEEK-MALL|100.00|" + state + "|" + tradeNo;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        service.callback("SIMULATED", HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), body);
    }
}
