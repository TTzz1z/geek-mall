package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentCallbackIdempotencyTest {
    @Test
    void duplicateSignedCallbackTransitionsAndPublishesOnce() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:payment_callback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE trade_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id BIGINT,payable_amount DECIMAL(18,2),paid_amount DECIMAL(18,2),status VARCHAR(32),paid_payment_id BIGINT,inventory_status VARCHAR(32),version INT,paid_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_payment(id BIGINT PRIMARY KEY,payment_no VARCHAR(64),order_id BIGINT,user_id BIGINT,channel VARCHAR(32),merchant_no VARCHAR(64),amount DECIMAL(18,2),status VARCHAR(32),channel_trade_no VARCHAR(96),created_at TIMESTAMP,updated_at TIMESTAMP,paid_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_payment_callback(id BIGINT PRIMARY KEY,channel VARCHAR(32),callback_id VARCHAR(96),payload_hash VARCHAR(64),payment_no VARCHAR(64),process_status VARCHAR(32),detail VARCHAR(500),created_at TIMESTAMP,processed_at TIMESTAMP,UNIQUE(channel,callback_id))");
        jdbc.execute("CREATE TABLE trade_sub_order(id BIGINT PRIMARY KEY,order_id BIGINT,merchant_id BIGINT,status VARCHAR(32),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,sub_order_id BIGINT,payable_amount DECIMAL(18,2))");
        jdbc.execute("CREATE TABLE trade_discount_reservation(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_order_timeout(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE trade_order_log(id BIGINT PRIMARY KEY,order_id BIGINT,from_status VARCHAR(32),to_status VARCHAR(32),operator_type VARCHAR(32),operator_id BIGINT,detail VARCHAR(500),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE event_outbox(id BIGINT PRIMARY KEY,event_id VARCHAR(64),aggregate_type VARCHAR(64),aggregate_id VARCHAR(64),event_type VARCHAR(128),payload_json VARCHAR(2000),status VARCHAR(32),retry_count INT DEFAULT 0,next_retry_at TIMESTAMP,created_at TIMESTAMP,published_at TIMESTAMP,UNIQUE(event_id))");
        jdbc.execute("CREATE TABLE trade_compensation_task(id BIGINT PRIMARY KEY,task_type VARCHAR(64),business_no VARCHAR(64),payload_json VARCHAR(100),status VARCHAR(32),retry_count INT DEFAULT 0,next_retry_at TIMESTAMP,last_error VARCHAR(500),created_at TIMESTAMP,updated_at TIMESTAMP,lease_token VARCHAR(36),lease_until TIMESTAMP,UNIQUE(task_type,business_no))");
        jdbc.execute("CREATE TABLE trade_order_discount(order_id BIGINT,discount_type VARCHAR(32))");
        jdbc.execute("CREATE TABLE trade_reconcile_log(id BIGINT PRIMARY KEY,reconcile_type VARCHAR(64),business_no VARCHAR(64),difference_count INT,status VARCHAR(32),detail VARCHAR(500),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO trade_order VALUES(1,'GM1',4,100.00,0,'PENDING_PAYMENT',NULL,'RESERVED',0,NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO trade_payment VALUES(2,'PAY1',1,4,'SIMULATED','GEEK-MALL',100.00,'PENDING',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)");
        jdbc.update("INSERT INTO trade_sub_order VALUES(3,1,2001,'PENDING_PAYMENT',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO trade_order_item VALUES(10,1,3,100.00)");
        jdbc.update("INSERT INTO trade_discount_reservation VALUES(4,1,'FROZEN',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO trade_order_timeout VALUES(5,1,'PENDING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO trade_order_discount VALUES(1,'COUPON'),(1,'POINTS')");

        InventoryClient inventory = mock(InventoryClient.class);
        OrderService orders = mock(OrderService.class);
        MarketingClient marketing = mock(MarketingClient.class);
        MemberClient member = mock(MemberClient.class);
        ObjectMapper mapper = new ObjectMapper();
        TradeEventStore events = new TradeEventStore(jdbc, mapper);
        UnavailableRealPaymentAdapters adapters = new UnavailableRealPaymentAdapters(List.of(new SimulatedPaymentChannelAdapter()));
        String secret = "test-callback-secret";
        var tasks = new TradeTasks(jdbc, 120);
        PaymentService service = new PaymentService(
                jdbc, new DataSourceTransactionManager(ds), adapters, inventory, events, tasks,
                "GEEK-MALL", secret);

        PaymentController.Callback body = new PaymentController.Callback(
                "CB-1", "PAY1", "GEEK-MALL", new BigDecimal("100.00"), "SUCCESS", "MOCK-1");
        String canonical = "SIMULATED|CB-1|PAY1|GEEK-MALL|100.00|SUCCESS|MOCK-1";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        var first = service.callback("SIMULATED", signature, body);
        var duplicate = service.callback("SIMULATED", signature, body);

        assertThat(first.get("idempotentReplay")).isEqualTo(false);
        assertThat(duplicate.get("idempotentReplay")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=1", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_payment WHERE id=2", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT process_status FROM trade_payment_callback", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_payment_callback", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_type='payment.payment.succeeded.v1'", Integer.class)).isEqualTo(1);
        verifyNoInteractions(inventory, marketing, member);
        assertThat(jdbc.queryForList("SELECT task_type FROM trade_compensation_task WHERE status='PENDING'", String.class))
                .containsExactlyInAnyOrder("DEDUCT_STOCK", "SETTLE_ASSETS", "CREATE_FULFILLMENT");

        // A replacement worker takes over a task claimed by a crashed process.
        jdbc.update("UPDATE trade_compensation_task SET status='RUNNING',lease_token='old',lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE task_type='DEDUCT_STOCK'");
        PaymentService fulfillment = mock(PaymentService.class);
        var jobs = new TradeRecoveryJobs(jdbc, orders, inventory, mock(RefundService.class), marketing, member,
                fulfillment, mock(SettlementClient.class), mock(SeckillOrderService.class), mapper, tasks);
        jobs.compensate();
        jobs.compensate();
        assertThat(jdbc.queryForList("SELECT status FROM trade_compensation_task", String.class))
                .containsOnly("SUCCESS").hasSize(3);
        verify(inventory, times(1)).deduct("GM1");
        verify(marketing, times(1)).settleCoupon("GM1", "use");
        verify(member, times(1)).settlePoints("GM1", "use");
        verify(fulfillment, times(1)).createFulfillments("GM1");
    }
}
